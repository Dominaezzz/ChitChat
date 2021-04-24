package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.api.QueryKeys
import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.UnsignedDeviceInfo
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.SyncClient
import me.dominaezzz.chitchat.sdk.util.getSerializable
import me.dominaezzz.chitchat.sdk.util.SQLiteHelper
import me.dominaezzz.chitchat.sdk.util.savepoint
import me.dominaezzz.chitchat.sdk.util.setSerializable
import me.dominaezzz.chitchat.sdk.util.usingStatement
import java.nio.file.Path
import java.sql.Connection

class DeviceCache(
	private val scope: CoroutineScope,
	private val syncClient: SyncClient,
	private val client: HttpClient,
	private val loginSession: LoginSession,
	private val databaseFile: Path
) {
	private val sqlite = object : SQLiteHelper(databaseFile, 1) {
		override fun onCreate(connection: Connection) {
			connection.usingStatement { stmt ->
				stmt.execute("""
					CREATE TABLE tracked_users
					(
						userId     TEXT    NOT NULL PRIMARY KEY,
						isOutdated BOOLEAN NOT NULL DEFAULT FALSE,
						sync_token TEXT
					);
				""")
				stmt.execute("""
					CREATE TABLE device_list
					(
						userId     TEXT NOT NULL,
						deviceId   TEXT NOT NULL,
						algorithms TEXT NOT NULL CHECK (JSON_VALID(algorithms)),
						keys       TEXT NOT NULL CHECK (JSON_VALID(keys)),
						signatures TEXT NOT NULL CHECK (JSON_VALID(signatures)),
						unsigned   TEXT NOT NULL CHECK (JSON_VALID(unsigned)),
					
						json       TEXT GENERATED ALWAYS AS (
							JSON_OBJECT(
								'user_id', userId,
								'device_id', deviceId,
								'algorithms', JSON(algorithms),
								'keys', JSON(keys),
								'signatures', JSON(signatures),
								'unsigned', JSON(unsigned)
							)
						),
					
						PRIMARY KEY (userId, deviceId),
						FOREIGN KEY (userId) REFERENCES tracked_users (userId)
					);
				""")
			}
		}
	}

	init {
		syncClient.syncFlow.onEach { sync ->
			val lists = sync.deviceLists ?: return@onEach

			sqlite.usingWriteConnection { conn ->
				conn.prepareStatement(
					"UPDATE tracked_users SET isOutdated = TRUE, sync_token = ? WHERE userId = ?;").use { stmt ->
					for (userId in lists.changed) {
						stmt.setString(1, sync.nextBatch)
						stmt.setString(2, userId)
						stmt.executeUpdate()
					}
				}
				// conn.prepareStatement("DELETE FROM tracked_users WHERE userId = ?;").use { stmt ->
				// 	for (userId in lists.left) {
				// 		stmt.setString(1, userId)
				// 		stmt.executeUpdate()
				// 	}
				// }
			}
		}.launchIn(scope)
	}

	suspend fun getUserDevices(userId: String): List<DeviceKeys> {
		val cacheResult = sqlite.usingReadConnection { conn ->
			val query = """
				SELECT JSON_GROUP_ARRAY(JSON(json)) FILTER (WHERE dl.userId IS NOT NULL), isOutdated
				FROM tracked_users
				LEFT JOIN device_list dl USING (userId)
				WHERE userId = ?
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, userId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						val deviceKeys = rs.getSerializable<List<DeviceKeys>>(1)
						val isOutdated = rs.getBoolean(2)
						deviceKeys to isOutdated
					} else {
						null // no user
					}
				}
			}
		}

		if (cacheResult != null) {
			val (userDevices, isOutdated) = cacheResult
			if (!isOutdated) {
				return userDevices
			}
		}

		// TODO: Need to handle concurrency here.

		val token = if (cacheResult == null) {
			sqlite.usingWriteConnection { conn ->
				// TODO: What if userId doesn't even exist or isn't valid.
				conn.prepareStatement("INSERT INTO tracked_users(userId) VALUES (?);").use { stmt ->
					stmt.setString(1, userId)
					stmt.executeUpdate()
				}
			}
			null
		} else {
			sqlite.usingReadConnection { conn ->
				conn.prepareStatement("SELECT sync_token FROM tracked_users WHERE userId = ?;").use { stmt ->
					stmt.setString(1, userId)
					stmt.executeQuery().use { rs ->
						if (rs.next()) {
							rs.getString(1)
						} else {
							null
						}
					}
				}
			}
		}

		val response = client.rpc(
			QueryKeys(
				QueryKeys.Url(),
				QueryKeys.Body(deviceKeys = mapOf(userId to emptyList()), token = token)
			),
			loginSession.accessToken
		)
		val userDevices = response.deviceKeys.getValue(userId)

		sqlite.usingWriteConnection { conn ->
			conn.savepoint {
				conn.prepareStatement("DELETE FROM device_list WHERE userId = ?;").use { stmt ->
					stmt.setString(1, userId)
					stmt.executeUpdate()
				}

				val insertDeviceSQL = """
					INSERT INTO device_list(userId, deviceId, algorithms, keys, signatures, unsigned)
					VALUES (?, ?, ?, ?, ?, ?);
				"""
				conn.prepareStatement(insertDeviceSQL).use { stmt ->
					stmt.setString(1, userId)

					for ((deviceId, deviceKeys) in userDevices) {
						// If basic details don't match, then don't trust this device at all.
						// We also want to avoid saving a user's device as someone else's.
						if (userId != deviceKeys.userId || deviceId != deviceKeys.deviceId) {
							println("User($userId)'s device ($deviceId) published suspicious DeviceKeys(userId='${deviceKeys.userId}', deviceId='${deviceKeys.deviceId}').")
							continue
						}

						stmt.setString(1, deviceKeys.userId)
						stmt.setString(2, deviceKeys.deviceId)
						stmt.setSerializable(3, ListSerializer(String.serializer()), deviceKeys.algorithms)
						stmt.setSerializable(4, MapSerializer(String.serializer(), String.serializer()), deviceKeys.keys)
						stmt.setSerializable(5, MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())), deviceKeys.signatures)
						stmt.setSerializable(6, UnsignedDeviceInfo.serializer().nullable, deviceKeys.unsigned)
						stmt.executeUpdate()
					}
				}

				val markUpdatedSQL = """
					UPDATE tracked_users
					SET isOutdated = FALSE, sync_token = NULL
					WHERE userId = ? AND sync_token IS ?;
				"""
				val updates = conn.prepareStatement(markUpdatedSQL).use { stmt ->
					stmt.setString(1, userId)
					stmt.setString(2, token)
					stmt.executeUpdate()
				}

				if (updates == 0) {
					println("User($userId) was marked out of date while update was being made.")
				}
			}
		}

		return userDevices.values.toList()
	}
}
