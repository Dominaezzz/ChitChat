package me.dominaezzz.chitchat.db

import java.sql.Connection

fun Connection.getValue(key: String): String? {
	return prepareStatement("SELECT value FROM key_value_store WHERE key = ?;").use { stmt ->
		stmt.setString(1, key)
		stmt.executeQuery().use { rs ->
			if (rs.next()) {
				rs.getString(1)
			} else {
				null
			}
		}
	}
}

fun Connection.setValue(key: String, value: String?) {
	prepareStatement(
		"""
            INSERT INTO key_value_store(key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value;
        """
	).use {
		it.setString(1, key)
		it.setString(2, value)
		it.executeUpdate()
	}
}

fun Connection.getTimelineIdAndOrder(roomId: String, eventId: String): Pair<Int, Int> {
	return prepareStatement("SELECT timelineId, timelineOrder FROM room_events WHERE roomId = ? AND eventId = ?;").use { stmt ->
		stmt.setString(1, roomId)
		stmt.setString(2, eventId)
		stmt.executeQuery().use { rs ->
			if (!rs.next()) throw NoSuchElementException("Could not find event with roomId='$roomId' and eventId='$eventId'.")
			val timelineId = rs.getInt(1)
			val timelineOrder = rs.getInt(2) // Missing a NULL check here but we can think about that later.
			timelineId to timelineOrder
		}
	}
}
