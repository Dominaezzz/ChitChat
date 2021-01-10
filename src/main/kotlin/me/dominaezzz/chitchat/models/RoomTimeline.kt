package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.SyncStore

class RoomTimeline(
	private val room: Room,
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
				events = conn.getEventsBetween(room.id, 1, Int.MAX_VALUE)
			}
		}
		_events.value = events
	}

	private suspend fun loadFutureEvents() {
		room.timelineEvents
			.map { }
			.collect {
				val item = _events.value.last()
				val lastEvent = item.event
				val eventId = lastEvent.eventId

				val events: List<TimelineItem>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(room.id, eventId)
						if (timelineId != 0) {
							TODO("Our timeline was disconnected from the latest timeline. RIP.")
							// Probably best to clear everything and start again in
							// this case or attempt to restitch the separate timelines.
						}

						events = conn.getEventsBetween(room.id, timelineOrder + 1, Int.MAX_VALUE)
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
				if (!room.backPaginate(eventId, 20)) {
					throw CancellationException("Can not back paginate any further.")
				}
				println("Past events downloaded")

				var events: List<TimelineItem>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(room.id, eventId)
						if (timelineId != 0) { TODO("The timeline we just back-filled was disconnected from the latest timeline. RIP.") }

						events = conn.getEventsBetween(room.id, 1, timelineOrder - 1)
					}
				}

				_events.value = events + _events.value
			}
	}
}
