package me.dominaezzz.chitchat.db

import io.github.matrixkt.models.events.MatrixEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class TimelineItem(
	val event: MatrixEvent,
	val edits: List<JsonObject>,
	val reactions: Map<String, Int>
)

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

private val supportedEventTypes = listOf(
	"m.room.name",
	"m.room.topic",
	"m.room.member",
	"m.room.message",
	"m.room.avatar",
	"m.room.canonical_alias",

	"m.room.encryption",
	"m.room.encrypted",
	"m.room.create",
	"m.room.guest_access",
	"m.room.history_visibility",
	"m.room.join_rules"

	// "m.room.third_party_invite",
	// "m.room.pinned_events",
	// "m.room.power_levels",
	// "m.room.server_acl",
	// "m.room.tombstone"
)

@OptIn(ExperimentalStdlibApi::class)
fun Connection.getEventsBetween(roomId: String, from: Int, to: Int, eventTypes: List<String> = supportedEventTypes): List<TimelineItem> {
	val sql = """
		WITH
			 event_reactions(roomId, eventId, reaction_obj) AS (
				 SELECT roomId, eventId, JSON_GROUP_OBJECT(key, key_count)
				 FROM (
					 SELECT roomId, eventId, key, COUNT() AS key_count
					 FROM (
						 SELECT roomId, JSON_EXTRACT(content, '${'$'}."m.relates_to".event_id') AS eventId, JSON_EXTRACT(content, '${'$'}."m.relates_to".key') AS key
						 FROM room_events
						 WHERE type = 'm.reaction' AND JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') = 'm.annotation'
					 )
					 GROUP BY roomId, eventId, key
				 )
				 GROUP BY roomId, eventId
			 ),
			 msg_updates(roomId, sender, eventId, newContents) AS (
				 SELECT roomId, sender, eventId, JSON_GROUP_ARRAY(newContent)
				 FROM (
					 SELECT roomId, sender, JSON_EXTRACT(content, '${'$'}."m.relates_to".event_id') AS eventId, JSON_EXTRACT(content, '${'$'}."m.new_content"') AS newContent
					 FROM room_events
					 WHERE type = 'm.room.message' AND JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') = 'm.replace'
				 )
				 GROUP BY roomId, sender, eventId
			 ),
			 event_types(type) AS (SELECT value FROM JSON_EACH(?)),
			 msg_events AS (
				 SELECT roomId, eventId, sender, timelineOrder, JSON_OBJECT(
						 'type', type,
						 'content', JSON(content),
						 'event_id', eventId,
						 'sender', sender,
						 'origin_server_ts', timestamp,
						 'unsigned', JSON(unsigned),
						 'room_id', roomId,
						 'state_key', stateKey,
						 'prev_content', JSON(prevContent)
					 ) AS json
				 FROM room_events
				 JOIN event_types USING (type)
				 WHERE timelineId = 0 AND timelineOrder IS NOT NULL AND
					 (type != 'm.room.message' OR JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') IS NOT 'm.replace')
			 )
		SELECT json, reaction_obj, newContents, timelineOrder
		FROM msg_events
		LEFT JOIN event_reactions USING (roomId, eventId)
		LEFT JOIN msg_updates USING (roomId, sender, eventId)
		WHERE roomId = ? AND timelineOrder BETWEEN ? AND ?
		ORDER BY timelineOrder;
	"""

	return prepareStatement(sql).use { stmt ->
		stmt.setString(1, JsonArray(eventTypes.map { JsonPrimitive(it) }).toString())
		stmt.setString(2, roomId)
		stmt.setInt(3, from)
		stmt.setInt(4, to)
		stmt.executeQuery().use { rs ->
			buildList {
				while (rs.next()) {
					val event = rs.getSerializable(1, MatrixEvent.serializer())!!
					val reactions = rs.getSerializable(2, MapSerializer(String.serializer(), Int.serializer())).orEmpty()
					val msgUpdates = rs.getSerializable(3, ListSerializer(JsonObject.serializer())).orEmpty()
					add(TimelineItem(event, msgUpdates, reactions))
				}
			}
		}
	}
}

@OptIn(ExperimentalStdlibApi::class)
fun Connection.getEventChangesBetween(roomId: String, from: Int, to: Int, eventTypes: List<String> = supportedEventTypes): List<Pair<Int, TimelineItem>> {
	val sql = """
		WITH
			 event_reactions(roomId, eventId, reaction_obj, latestOrder) AS (
				 SELECT roomId, eventId, JSON_GROUP_OBJECT(key, key_count), MAX(latestOrder)
				 FROM (
					 SELECT roomId, eventId, key, COUNT() AS key_count, MAX(timelineOrder) AS latestOrder
					 FROM (
						  SELECT roomId, JSON_EXTRACT(content, '${'$'}."m.relates_to".event_id') AS eventId, JSON_EXTRACT(content, '${'$'}."m.relates_to".key') AS key, timelineOrder
						  FROM room_events
						  WHERE type = 'm.reaction' AND JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') = 'm.annotation'
					 )
					 GROUP BY roomId, eventId, key
				 )
				 GROUP BY roomId, eventId
			 ),
			 msg_updates(roomId, sender, eventId, newContents, latestOrder) AS (
				 SELECT roomId, sender, eventId, JSON_GROUP_ARRAY(newContent), MAX(timelineOrder) AS latestOrder
				 FROM (
					  SELECT roomId, sender, JSON_EXTRACT(content, '${'$'}."m.relates_to".event_id') AS eventId, JSON_EXTRACT(content, '${'$'}."m.new_content"') AS newContent, timelineOrder
					  FROM room_events
					  WHERE type = 'm.room.message' AND JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') = 'm.replace'
				 )
				 GROUP BY roomId, sender, eventId
			 ),
			 event_types(type) AS (SELECT value FROM JSON_EACH(?1)),
			 msg_events AS (
				 SELECT roomId, eventId, sender, timelineOrder, JSON_OBJECT(
						 'type', type,
						 'content', JSON(content),
						 'event_id', eventId,
						 'sender', sender,
						 'origin_server_ts', timestamp,
						 'unsigned', JSON(unsigned),
						 'room_id', roomId,
						 'state_key', stateKey,
						 'prev_content', JSON(prevContent)
					 ) AS json,
						ROW_NUMBER() OVER (PARTITION BY roomId ORDER BY timelineOrder) AS rowNum
				 FROM room_events
				 JOIN event_types USING (type)
				 WHERE timelineId = 0 AND timelineOrder IS NOT NULL AND
					 (type != 'm.room.message' OR JSON_EXTRACT(content, '${'$'}."m.relates_to".rel_type') IS NOT 'm.replace')
			 )
		SELECT json, reaction_obj, newContents, rowNum
		FROM msg_events
		LEFT JOIN event_reactions USING (roomId, eventId)
		LEFT JOIN msg_updates USING (roomId, sender, eventId)
		WHERE roomId = ?2 AND timelineOrder BETWEEN ?3 AND ?4 AND
			  MAX(IFNULL(msg_updates.latestOrder, timelineOrder), IFNULL(event_reactions.latestOrder, timelineOrder)) > ?4
		ORDER BY timelineOrder;
	"""

	return prepareStatement(sql).use { stmt ->
		stmt.setString(1, JsonArray(eventTypes.map { JsonPrimitive(it) }).toString())
		stmt.setString(2, roomId)
		stmt.setInt(3, from)
		stmt.setInt(4, to)
		stmt.executeQuery().use { rs ->
			buildList {
				while (rs.next()) {
					val event = rs.getSerializable(1, MatrixEvent.serializer())!!
					val reactions = rs.getSerializable(2, MapSerializer(String.serializer(), Int.serializer())).orEmpty()
					val msgUpdates = rs.getSerializable(3, ListSerializer(JsonObject.serializer())).orEmpty()
					val idx = rs.getInt(4)
					add(idx to TimelineItem(event, msgUpdates, reactions))
				}
			}
		}
	}
}
