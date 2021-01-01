package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.QueryKeysRequest
import io.github.matrixkt.models.UnsignedDeviceInfo
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.olm.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.LoginSession
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.crypto.usingUtility
import me.dominaezzz.chitchat.sdk.crypto.verifyEd25519Signature
import java.sql.Connection

class SyncClientImpl(
	private val scope: CoroutineScope,
	private val loginSession: LoginSession,
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore,
	private val store: SyncStore
) : SyncClient {
	private val shareConfig = SharingStarted.WhileSubscribed(1000)

	private val _syncFlow = MutableSharedFlow<SyncResponse>()
	override val syncFlow: SharedFlow<SyncResponse> = _syncFlow

	override suspend fun sync(timeout: Long?, setPresence: Presence?) {
		val syncToken = store.getSyncToken()

		println("Syncing with '$syncToken' as token")
		val sync = client.eventApi.sync(since = syncToken, setPresence = setPresence, timeout = timeout)

		println("Saving sync response")
		store.storeSync(sync, syncToken)
		println("Saved sync response")

		// Should cancellation be disabled here?
		// We don't really want to miss a sync if it's been persisted.
		_syncFlow.emit(sync)
	}

	override val joinedRooms: Flow<Map<String, Room>> = flow {
		var rooms = store.getJoinedRooms(loginSession.userId).associateWith { createRoom(it) }
		emit(rooms)

		syncFlow.mapNotNull { it.rooms }.collect { updates ->
			var wasUpdated = false
			for (roomId in updates.join.keys) {
				if (roomId !in rooms) {
					rooms += roomId to createRoom(roomId)
					wasUpdated = true
				}
			}
			for (roomId in updates.leave.keys) {
				if (roomId in rooms) {
					rooms -= roomId
					wasUpdated = true
				}
			}
			if (wasUpdated) {
				emit(rooms)
			}
		}
	}.shareIn(scope, shareConfig, 1)

	private fun createRoom(roomId: String): Room {
		return RoomImpl(roomId, scope, syncFlow, client, store)
	}


	private val accountDataFlowMap = MapOfFlows<String, JsonObject?> { type ->
		syncFlow.mapNotNull { it.accountData }
			.transform { data -> data.events.forEach { emit(it) } }
			.filter { it.type == type }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = store.getAccountData(type)
				emit(content)
			}
			.shareIn(scope, shareConfig, 1)
	}

	override fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?> {
		return accountDataFlowMap.getFlow(type).decodeJson(serializer)
	}


	override suspend fun getUserDevices(userId: String): List<DeviceKeys>? {
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

	override suspend fun getUserDevice(userId: String, deviceId: String): DeviceKeys? {
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
		private val deleteDevicesStmt = conn.prepareStatement("DELETE FROM device_list WHERE userId = ? AND deviceId = ?;")
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
				setSerializable(6, UnsignedDeviceInfo.serializer(), deviceKeys.unsigned)
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
		usingConnection { conn ->
			val utils = DeviceInsertUtils(conn)
			try {
				val userMap = withContext(Dispatchers.IO) {
					userIds.asSequence()
						.mapNotNull { userId -> utils.getDeviceListState(userId)?.let { userId to it } }
						.filter { (_, state) -> state.first }
						.map { (userId, state) -> userId to state.second }
						.toMap()
				}

				val request = QueryKeysRequest(deviceKeys = userMap.keys.associateWith { emptyList() })
				val response = client.keysApi.queryKeys(request)

				usingUtility { utility ->
					for ((userId, devices) in response.deviceKeys) {
						dbSemaphore.withPermit {
							withContext(Dispatchers.IO) {
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
