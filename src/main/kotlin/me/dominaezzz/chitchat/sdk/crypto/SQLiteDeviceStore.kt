package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.models.DeviceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import me.dominaezzz.chitchat.db.getSerializable
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SQLiteDeviceStore(private val databaseFile: Path): DeviceStore {
	private val writeSemaphore = Semaphore(1)

	private fun createConnection(): Connection {
		val config = SQLiteConfig()
		config.enforceForeignKeys(true)
		config.setJournalMode(SQLiteConfig.JournalMode.WAL)
		// config.setReadOnly()

		return DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}", config.toProperties())
	}

	private suspend inline fun <T> usingReadConnection(crossinline block: (Connection) -> T): T {
		return withContext(Dispatchers.IO) {
			createConnection().use(block)
		}
	}
	private suspend inline fun <T> usingWriteConnection(crossinline block: (Connection) -> T): T {
		return writeSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				createConnection().use { conn ->
					conn.autoCommit = false
					block(conn)
				}
			}
		}
	}

	suspend fun <T> read(block: (Connection) -> T): T {
		return usingReadConnection { block(it) }
	}
	suspend fun <T> write(block: (Connection) -> T): T {
		return usingWriteConnection { block(it) }
	}

	override suspend fun getUserDevice(userId: String, deviceId: String): Pair<DeviceKeys?, Boolean>? {
		return usingReadConnection { conn ->
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
		return usingReadConnection { conn ->
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
