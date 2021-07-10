package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.decodeFromJsonElement
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.SQLiteSyncStore
import me.dominaezzz.chitchat.sdk.core.SyncStore
import me.dominaezzz.chitchat.sdk.util.getSerializable
import me.dominaezzz.chitchat.sdk.util.getTimelineIdAndOrder
import me.dominaezzz.chitchat.sdk.util.setSerializable
import me.dominaezzz.chitchat.util.update
import java.sql.Connection

class RoomTimeline(
	private val room: Room,
	private val store: SyncStore,
	private val localEcho: LocalEcho
) {
	private val _state = MutableStateFlow(State(emptyList(), emptySet()))

	data class State(
		val events: List<MatrixEvent>,
		val txnIds: Set<String>
	)

	val shouldBackPaginate = MutableStateFlow(false)
	val state: StateFlow<State> = _state.asStateFlow()

	suspend fun run() {
		initialLoad()
		coroutineScope {
			launch { loadFutureEvents() }
			launch { loadPastEvents() }
		}
	}

	private suspend fun initialLoad() {
		check(store is SQLiteSyncStore)

		_state.value = State(
			store.read { conn ->
				conn.getEventsBetween(room.id, 1, Int.MAX_VALUE)
			},
			emptySet()
		)
	}

	private suspend fun loadFutureEvents() {
		check(store is SQLiteSyncStore)

		coroutineScope {
			localEcho.sentMessages
				.onEach { sent ->
					// Wait for remote echo.
					_state.first { sent.txnId in it.txnIds }
					// Then we can delete local echo.
					_state.update { it.copy(txnIds = it.txnIds - sent.txnId) }
				}
				.launchIn(this)

			val tidBuffer = mutableListOf<String>()
			room.timelineEvents
				.onEach { syncEvent ->
					val lastEvent = _state.value.events.last()
					val eventId = lastEvent.eventId

					val events = store.read { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(room.id, eventId)
						if (timelineId != 0) {
							TODO("Our timeline was disconnected from the latest timeline. RIP.")
							// Probably best to clear everything and start again in
							// this case or attempt to restitch the separate timelines.
						}

						conn.getEventsBetween(room.id, timelineOrder + 1, Int.MAX_VALUE)
					}

					events.asSequence()
						.map { it.unsigned }
						.plus(syncEvent.unsigned)
						.filterNotNull()
						.map { MatrixJson.decodeFromJsonElement<UnsignedData>(it) }
						.mapNotNullTo(tidBuffer) { it.transactionId }

					_state.update { State(it.events + events, it.txnIds + tidBuffer) }
					tidBuffer.clear()
				}
				.launchIn(this)
		}
	}

	private suspend fun loadPastEvents() {
		check(store is SQLiteSyncStore)

		shouldBackPaginate.filter { it }
			.conflate()
			.collect {
				// TODO: What if this is empty though... like if all the events in
				//  the database are not supported.
				val targetEvent = _state.value.events.first()
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

				_state.update { it.copy(events = events + it.events) }
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
	private fun Connection.getEventsBetween(roomId: String, from: Int, to: Int, eventTypes: List<String> = supportedEventTypes): List<MatrixEvent> {
		val sql = """
			WITH event_types(type) AS (SELECT value FROM JSON_EACH(?))
			SELECT json
			FROM room_events
			JOIN event_types USING (type)
			WHERE roomId = ? AND timelineId = 0 AND timelineOrder IS NOT NULL AND timelineOrder BETWEEN ? AND ?
			ORDER BY timelineOrder;
		"""

		return prepareStatement(sql).use { stmt ->
			stmt.setSerializable(1, ListSerializer(String.serializer()), eventTypes)
			stmt.setString(2, roomId)
			stmt.setInt(3, from)
			stmt.setInt(4, to)
			stmt.executeQuery().use { rs ->
				buildList {
					while (rs.next()) {
						val event = rs.getSerializable<MatrixEvent>(1)
						add(event)
					}
				}
			}
		}
	}
}
