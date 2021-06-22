package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.sync.Event
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.sdk.util.*
import java.nio.file.Path
import java.sql.Connection

class AppDatabase(databasePath: Path) {
	private val helper = object : SQLiteHelper(databasePath, 1) {
		override fun onCreate(connection: Connection) {
			connection.usingStatement { stmt ->
				stmt.execute("""
					CREATE TABLE key_value_store
					(
						key   TEXT PRIMARY KEY NOT NULL,
						value TEXT
					);
				""")
				stmt.execute("""
					CREATE TABLE device_events
					(
						id      INTEGER PRIMARY KEY AUTOINCREMENT,
						type    TEXT NOT NULL,
						content TEXT NOT NULL,
						sender  TEXT NOT NULL
					);
				""")
			}
		}
	}

	suspend fun getValue(key: String): String? {
		return helper.usingReadConnection { it.getValue(key) }
	}

	suspend fun setValue(key: String, value: String?) {
		return helper.usingWriteConnection { it.setValue(key, value) }
	}

	suspend fun insertDeviceEvents(events: Iterable<Event>) {
		helper.usingWriteConnection { conn ->
			conn.transaction {
				val sql = "INSERT INTO device_events(type, content, sender) VALUES (?, ?, ?);"
				conn.prepareStatement(sql).use { stmt ->
					for (event in events) {
						stmt.setString(1, event.type)
						stmt.setSerializable(2, JsonObject.serializer(), event.content)
						stmt.setString(3, event.sender)
						stmt.executeUpdate()
					}
				}
			}
		}
	}
}
