package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.QueryKeysRequest
import io.github.matrixkt.models.UnsignedDeviceInfo
import io.github.matrixkt.olm.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import me.dominaezzz.chitchat.db.savepoint
import me.dominaezzz.chitchat.db.setSerializable
import me.dominaezzz.chitchat.sdk.core.SyncClient
import java.sql.Connection

class DeviceManager(
	private val scope: CoroutineScope,
	private val client: MatrixClient,
	private val syncClient: SyncClient,
	private val store: SQLiteDeviceStore
) {
	init {
		// Should this be setup outside?
		syncClient.syncFlow.onEach { sync ->
			val lists = sync.deviceLists ?: return@onEach

			store.write { conn ->
				conn.prepareStatement(
					"UPDATE tracked_users SET isOutdated = TRUE, sync_token = ? WHERE userId = ?;").use { stmt ->
					for (userId in lists.changed) {
						stmt.setString(1, sync.nextBatch)
						stmt.setString(2, userId)
						stmt.executeUpdate()
					}
				}
				conn.prepareStatement("DELETE FROM tracked_users WHERE userId = ?;").use { stmt ->
					for (userId in lists.left) {
						stmt.setString(1, userId)
						stmt.executeUpdate()
					}
				}
			}
		}.launchIn(scope)
	}

	suspend fun getUserDevices(userId: String): List<DeviceKeys>? {
		val userDevices = store.getUserDevices(userId)
		if (userDevices == null) {
			// This mean we're not tracking this user, at least not yet.
			val response = client.keysApi.queryKeys(QueryKeysRequest(deviceKeys = mapOf(userId to emptyList())))
			val devices = response.deviceKeys[userId]?.values ?: return null
			return usingUtility { utility ->
				devices.filter { runCatching { utility.verifyEd25519Signature(it) }.isSuccess }
			}
		}

		val (devices, isOutdated) = userDevices
		if (!isOutdated) {
			return devices
		}

		updateUserDevices(setOf(userId))

		return store.getUserDevices(userId)?.first
	}

	suspend fun getUserDevice(userId: String, deviceId: String): DeviceKeys? {
		val userDevice = store.getUserDevice(userId, deviceId)
		if (userDevice == null) {
			// This mean we're not tracking this user, at least not yet.
			val response = client.keysApi.queryKeys(QueryKeysRequest(deviceKeys = mapOf(userId to listOf(deviceId))))
			val device = response.deviceKeys[userId]?.get(deviceId) ?: return null
			val validSignature = usingUtility { runCatching { it.verifyEd25519Signature(device) }.isSuccess }
			return device.takeIf { validSignature }
		}

		val (deviceKeys, isOutdated) = userDevice
		if (!isOutdated) {
			return deviceKeys
		}

		updateUserDevices(setOf(userId))

		return store.getUserDevice(userId, deviceId)?.first
	}

	private class DeviceInsertUtils(conn: Connection) {
		private val getStateStmt = conn.prepareStatement("SELECT isOutdated, sync_token FROM tracked_users WHERE userId = ?;")
		private val updateStateStmt = conn.prepareStatement("UPDATE tracked_users SET isOutdated = FALSE, sync_token = NULL WHERE userId = ? AND sync_token IS ?;")
		private val deleteDevicesStmt = conn.prepareStatement("DELETE FROM device_list WHERE userId = ?;")
		private val addDeviceStmt = conn.prepareStatement("INSERT INTO device_list(userId, deviceId, algorithms, keys, signatures, unsigned) VALUES (?, ?, ?, ?, ?, ?)")

		fun getDeviceListState(userId: String): Pair<Boolean, String?>? {
			getStateStmt.setString(1, userId)
			return getStateStmt.executeQuery().use { rs ->
				if (rs.next()) {
					rs.getBoolean(1) to rs.getString(2)
				} else {
					null
				}
			}
		}

		fun updateDeviceListState(userId: String, syncToken: String?) {
			updateStateStmt.setString(1, userId)
			updateStateStmt.setString(2, syncToken)
			updateStateStmt.executeUpdate()
		}

		fun deleteDevices(userId: String): Int {
			deleteDevicesStmt.setString(1, userId)
			return deleteDevicesStmt.executeUpdate()
		}

		fun insertDevice(deviceKeys: DeviceKeys) {
			with(addDeviceStmt) {
				setString(1, deviceKeys.userId)
				setString(2, deviceKeys.deviceId)
				setSerializable(3, ListSerializer(String.serializer()), deviceKeys.algorithms)
				setSerializable(4, MapSerializer(String.serializer(), String.serializer()), deviceKeys.keys)
				setSerializable(5, MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())), deviceKeys.signatures)
				setSerializable(6, UnsignedDeviceInfo.serializer().nullable, deviceKeys.unsigned)
				executeUpdate()
			}
		}

		fun close() {
			addDeviceStmt.close()
			deleteDevicesStmt.close()
			updateStateStmt.close()
			getStateStmt.close()
		}
	}

	private suspend fun updateUserDevices(userIds: Set<String>) {
		// Needs some serious refactoring.

		val userMap = store.read { conn ->
			val utils = DeviceInsertUtils(conn)
			try {
				userIds.asSequence()
					.mapNotNull { userId -> utils.getDeviceListState(userId)?.let { userId to it } }
					.filter { (_, state) -> state.first }
					.map { (userId, state) -> userId to state.second }
					.toMap()
			} finally {
				utils.close()
			}
		}

		val request = QueryKeysRequest(deviceKeys = userMap.keys.associateWith { emptyList() })
		val response = client.keysApi.queryKeys(request)

		store.write { conn ->
			val utils = DeviceInsertUtils(conn)
			try {
				usingUtility { utility ->
					for ((userId, devices) in response.deviceKeys) {
						// Savepoint per user.
						conn.savepoint {
							val (isOutdated, syncToken) = utils.getDeviceListState(userId) ?: return@savepoint
							// If user couldn't be found in db.
							// Then user left shared room(s) while we were querying.
							// So discard data.

							if (!isOutdated) {
								// Consider forcing an update in some circumstances.
								return@savepoint
							}

							if (userMap.getValue(userId) != syncToken) {
								println("User($userId) was marked out of date while update was being made.")
								// return@savepoint ?
							}

							utils.deleteDevices(userId)

							// Otherwise process user's potentially new devices.
							for ((deviceId, deviceKeys) in devices) {
								// If basic details don't match, then don't trust this device at all.
								if (userId != deviceKeys.userId || deviceId != deviceKeys.deviceId) {
									println("User($userId)'s device ($deviceId) published suspicious DeviceKeys(userId='${deviceKeys.userId}', deviceId='${deviceKeys.deviceId}').")
									continue
								}

								try {
									utility.verifyEd25519Signature(deviceKeys)
								} catch (e: Exception) {
									e.printStackTrace()
									println("User($userId)'s device ($deviceId) failed signature verification.")
									continue
								}

								utils.insertDevice(deviceKeys)
							}

							utils.updateDeviceListState(userId, userMap.getValue(userId))
						}
					}
				}
			} finally {
				utils.close()
			}
		}
	}

	private fun Utility.verifyEd25519Signature(deviceKeys: DeviceKeys) {
		verifyEd25519Signature(
			deviceKeys.userId,
			deviceKeys.deviceId,
			deviceKeys.keys.getValue("ed25519:${deviceKeys.deviceId}"),
			DeviceKeys.serializer(),
			deviceKeys
		)
	}
}
