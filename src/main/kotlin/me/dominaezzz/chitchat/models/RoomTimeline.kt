package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.events.MatrixEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.SQLiteSyncStore
import me.dominaezzz.chitchat.sdk.core.SyncStore
import java.sql.Connection

class RoomTimeline(
	private val room: Room,
	private val store: SyncStore
) {
	private val _events = MutableStateFlow<List<TimelineItem>>(emptyList())

	val shouldBackPaginate = MutableStateFlow(false)
	val events: StateFlow<List<TimelineItem>> get() = _events

	suspend fun run() {
		initialLoad()
		coroutineScope {
			launch { loadFutureEvents() }
			launch { loadPastEvents() }
		}
	}

	private suspend fun initialLoad() {
		check(store is SQLiteSyncStore)

		_events.value = store.read { conn ->
			conn.getEventsBetween(room.id, 1, Int.MAX_VALUE)
		}
	}

	private suspend fun loadFutureEvents() {
		check(store is SQLiteSyncStore)

		room.timelineEvents
			.map { }
			.collect {
				val item = _events.value.last()
				val lastEvent = item.event
				val eventId = lastEvent.eventId

				_events.value += store.read { conn ->
					val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(room.id, eventId)
					if (timelineId != 0) {
						TODO("Our timeline was disconnected from the latest timeline. RIP.")
						// Probably best to clear everything and start again in
						// this case or attempt to restitch the separate timelines.
					}

					conn.getEventsBetween(room.id, timelineOrder + 1, Int.MAX_VALUE)
				}
			}
	}

	private suspend fun loadPastEvents() {
		check(store is SQLiteSyncStore)

		shouldBackPaginate.filter { it }
			.conflate()
			.collect {
				// TODO: What if this is empty though... like if all the events in
				//  the database are not supported.
				val item = _events.value.first()
				val targetEvent = item.event
				val eventId = targetEvent.eventId

				println("Back paginating")
				if (!room.backPaginate(eventId, 20)) {
					throw CancellationException("Can not back paginate any further.")
				}
				println("Past events downloaded")

				val events = store.read { conn ->
					val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(room.id, eventId)
					if (timelineId != 0) { TODO("The timeline we just back-filled was disconnected from the latest timeline. RIP.") }

					conn.getEventsBetween(room.id, 1, timelineOrder - 1)
				}

				_events.value = events + _events.value
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
	private fun Connection.getEventsBetween(roomId: String, from: Int, to: Int, eventTypes: List<String> = supportedEventTypes): List<TimelineItem> {
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
						val event = rs.getSerializable(1, MatrixEvent.serializer())
						val reactions = rs.getSerializable(2, MapSerializer(String.serializer(), Int.serializer()).nullable).orEmpty()
						val msgUpdates = rs.getSerializable(3, ListSerializer(JsonObject.serializer()).nullable).orEmpty()
						add(TimelineItem(event, msgUpdates, reactions))
					}
				}
			}
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	private fun Connection.getEventChangesBetween(roomId: String, from: Int, to: Int, eventTypes: List<String> = supportedEventTypes): List<Pair<Int, TimelineItem>> {
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
						val event = rs.getSerializable(1, MatrixEvent.serializer())
						val reactions = rs.getSerializable(2, MapSerializer(String.serializer(), Int.serializer()).nullable).orEmpty()
						val msgUpdates = rs.getSerializable(3, ListSerializer(JsonObject.serializer()).nullable).orEmpty()
						val idx = rs.getInt(4)
						add(idx to TimelineItem(event, msgUpdates, reactions))
					}
				}
			}
		}
	}
}
