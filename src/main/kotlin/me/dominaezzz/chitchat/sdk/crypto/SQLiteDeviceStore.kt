package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.models.DeviceKeys
import kotlinx.serialization.builtins.ListSerializer
import me.dominaezzz.chitchat.db.getSerializable
import me.dominaezzz.chitchat.db.usingStatement
import me.dominaezzz.chitchat.sdk.util.SQLiteHelper
import java.nio.file.Path
import java.sql.Connection

class SQLiteDeviceStore(private val databaseFile: Path): DeviceStore {
	private val helper = object : SQLiteHelper(databaseFile, 1) {
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

	suspend fun <T> read(block: (Connection) -> T): T {
		return helper.usingReadConnection(block)
	}
	suspend fun <T> write(block: (Connection) -> T): T {
		return helper.usingWriteConnection(block)
	}

	override suspend fun getUserDevice(userId: String, deviceId: String): Pair<DeviceKeys?, Boolean>? {
		return helper.usingReadConnection { conn ->
			val query = """
				SELECT json, isOutdated
				FROM tracked_users
				LEFT JOIN device_list dl ON tracked_users.userId = dl.userId AND dl.deviceId = ?
				WHERE tracked_users.userId = ?
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, userId)
				stmt.setString(2, deviceId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						val deviceKeys = rs.getSerializable(1, DeviceKeys.serializer())
						val isOutdated = rs.getBoolean(2)
						deviceKeys to isOutdated
					} else {
						null // no user
					}
				}
			}
		}
	}

	override suspend fun getUserDevices(userId: String): Pair<List<DeviceKeys>, Boolean>? {
		return helper.usingReadConnection { conn ->
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
						val deviceKeys = rs.getSerializable(1, ListSerializer(DeviceKeys.serializer()))!!
						val isOutdated = rs.getBoolean(2)
						deviceKeys to isOutdated
					} else {
						null // no user
					}
				}
			}
		}
	}
}
