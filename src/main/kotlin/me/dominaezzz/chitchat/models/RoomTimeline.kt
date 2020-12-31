package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Direction
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.SyncStore

class RoomTimeline(
	private val roomId: String,
	private val syncFlow: Flow<SyncResponse>,
	private val client: MatrixClient,
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
		val events: List<TimelineItem>
		withContext(Dispatchers.IO) {
			usingConnection { conn ->
				events = conn.getEventsBetween(roomId, 1, Int.MAX_VALUE)
			}
		}
		_events.value = events
	}

	private suspend fun loadFutureEvents() {
		syncFlow.mapNotNull { it.rooms?.join?.get(roomId) }
			.filterNot { it.timeline?.events.isNullOrEmpty() }
			.map { }
			.collect {
				val item = _events.value.last()
				val lastEvent = item.event
				val eventId = lastEvent.eventId

				val events: List<TimelineItem>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(roomId, eventId)
						if (timelineId != 0) {
							TODO("Our timeline was disconnected from the latest timeline. RIP.")
							// Probably best to clear everything and start again in
							// this case or attempt to restitch the separate timelines.
						}

						events = conn.getEventsBetween(roomId, timelineOrder + 1, Int.MAX_VALUE)
					}
				}

				_events.value += events
			}
	}

	private suspend fun loadPastEvents() {
		shouldBackPaginate.filter { it }
			.conflate()
			.collect {
				// TODO: What if this is empty though... like if all the events in
				// the database are not supported.
				val item = _events.value.first()
				val targetEvent = item.event
				val eventId = targetEvent.eventId

				println("Back paginating")
				backFill(roomId, eventId)
				println("Past events downloaded")

				var events: List<TimelineItem>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(roomId, eventId)
						if (timelineId != 0) { TODO("The timeline we just back-filled was disconnected from the latest timeline. RIP.") }

						events = conn.getEventsBetween(roomId, 1, timelineOrder - 1)
					}
				}

				_events.value = events + _events.value
			}
	}

	private suspend fun backFill(roomId: String, eventId: String) {
		val token = withContext(Dispatchers.IO) {
			usingConnection { conn ->
				// Find first event in timeline and get corresponding token.
				conn.prepareStatement("""
					SELECT token
					FROM room_pagination_tokens
					JOIN room_events head_events USING(roomId, eventId)
					JOIN room_events tail_events USING(roomId, timelineId)
					WHERE roomId = ? AND tail_events.eventId = ?;
				""").use { stmt ->
					stmt.setString(1, roomId)
					stmt.setString(2, eventId)
					stmt.executeQuery().use { rs ->
						if (!rs.next()) {
							println("No token for back pagination")
							// Cannot paginate backwards from this event
							throw CancellationException("Can not back paginate any further.")
						}
						rs.getString(1)
					}
				}
			}
		}

		val response = client.roomApi.getRoomEvents(roomId, token, null, Direction.B, 20)

		store.storeTimelineEvents(roomId, response)
	}
}
