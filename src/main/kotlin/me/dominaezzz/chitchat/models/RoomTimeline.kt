package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Direction
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import me.dominaezzz.chitchat.db.*
import java.sql.Types

class RoomTimeline(
	private val roomId: String,
	private val syncFlow: Flow<SyncResponse>,
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore
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
		val (token, headEventId) = withContext(Dispatchers.IO) {
			usingConnection { conn ->
				// Find first event in timeline and get corresponding token.
				conn.prepareStatement("""
					SELECT token, first_timeline_event.eventId
					FROM room_pagination_tokens
					JOIN room_events first_timeline_event USING(roomId, eventId)
					JOIN room_events first_supported_event USING(roomId, timelineId)
					WHERE roomId = ? AND first_supported_event.eventId = ? AND first_timeline_event.timelineOrder == 1;
				""").use { stmt ->
					stmt.setString(1, roomId)
					stmt.setString(2, eventId)
					stmt.executeQuery().use { rs ->
						if (!rs.next()) {
							println("No token for back pagination")
							// Cannot paginate backwards from this event
							throw CancellationException("Can not back paginate any further.")
						}
						rs.getString(1) to rs.getString(2)
					}
				}
			}
		}

		val response = client.roomApi.getRoomEvents(roomId, token, null, Direction.B, 20)
		val newEvents = response.chunk

		dbSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					conn.autoCommit = false

					conn.prepareStatement("DELETE FROM room_pagination_tokens WHERE roomId = ? AND eventId = ?;").use {
						it.setString(1, roomId)
						it.setString(2, headEventId)
						val changes = it.executeUpdate()
						if (changes != 1) {
							conn.rollback()
							// Pagination token already consumed while we were trying to consume it.
							return@usingConnection
						}
					}

					if (newEvents.isNullOrEmpty()) {
						conn.commit()
						return@usingConnection
					}
					println(newEvents.size)

					val timelineId = run {
						val (id, order) = conn.getTimelineIdAndOrder(roomId, headEventId)
						check(order == 1) { "Can only back paginate from edge of timeline but was given $order." }
						id
					}
					val shiftTimelineStmt = conn.prepareStatement("UPDATE room_events SET timelineOrder = timelineOrder + ? WHERE roomId = ? AND timelineId = ?;")
					shiftTimelineStmt.setString(2, roomId)
					shiftTimelineStmt.setInt(3, timelineId)

					val eventStmt = conn.prepareStatement("""
                        INSERT INTO room_events(
                            roomId, eventId, type, content, sender, stateKey, prevContent, timestamp, unsigned,
                            timelineId, timelineOrder
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(roomId, eventId) DO UPDATE
                            SET timelineOrder = excluded.timelineOrder, prevContent = excluded.prevContent, unsigned = excluded.unsigned
                            WHERE timelineOrder IS NULL;
                    """)

					try {
						shiftTimelineStmt.setInt(1, newEvents.size)
						shiftTimelineStmt.executeUpdate()

						var timelineOrder = newEvents.size
						var overlappingEvents = 0 // kludge for when synapse returns event out of bounds.

						for (event in newEvents) {
							eventStmt.setString(1, roomId)
							eventStmt.setString(2, event.eventId)
							eventStmt.setString(3, event.type)
							eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
							eventStmt.setString(5, event.sender)
							eventStmt.setString(6, event.stateKey)
							eventStmt.setSerializable(7, JsonElement.serializer(), event.prevContent)
							eventStmt.setLong(8, event.originServerTimestamp)
							eventStmt.setSerializable(9, UnsignedData.serializer(), event.unsigned)
							eventStmt.setInt(10, timelineId)
							eventStmt.setInt(11, timelineOrder)
							val changes = eventStmt.executeUpdate()
							if (changes == 0) {
								val (id, _) = conn.getTimelineIdAndOrder(roomId, event.eventId)
								// If server returned overlapping event ... we skip it.
								if (id == timelineId) {
									check(newEvents.size == (timelineOrder - overlappingEvents)) {
										"Server returned inconsistent duplicate message events!"
									}
									println("Overlap!!!")
									overlappingEvents++
									continue
								}

								// If no rows were inserted, then there was a conflict between two timeline events.
								// which means we have to stitch two timeline chunks together.
								break
								// By just breaking here, we assume we already have all the skipped events stored in earlier timeline.
							}
							timelineOrder--
						}

						if (overlappingEvents > 0) {
							// Deallocate the space for the duplicate events
							shiftTimelineStmt.setInt(1, -overlappingEvents)
							shiftTimelineStmt.executeUpdate()
							timelineOrder -= overlappingEvents
						}

						if (timelineOrder > 0) {
							// Will throw if server is not compliant.
							// check(response.state.isNullOrEmpty()) { "There shouldn't be any compressed state events if we have to stitch." }

							// We're stitching.
							println("Stitching timeline $timelineId with ${timelineId + 1}")

							// Get highest order from next youngest timeline
							val maxOrderOfPreviousTimeline = conn.prepareStatement("SELECT MAX(timelineOrder) FROM room_events WHERE roomId = ? AND timelineId = ?").use { stmt ->
								stmt.setString(1, roomId)
								stmt.setInt(2, timelineId + 1)
								stmt.executeQuery().use { rs ->
									check(rs.next())
									rs.getInt(1)
								}
							}

							// Shift all our events forward to accommodate older timeline.
							shiftTimelineStmt.setInt(1, maxOrderOfPreviousTimeline - timelineOrder)
							shiftTimelineStmt.executeUpdate()

							// Merge timelines (and maintain contiguous timelineIds).
							conn.withoutIndex("room_events", "compressed_state") {
								conn.prepareStatement("UPDATE room_events SET timelineId = timelineId - 1 WHERE roomId = ? AND timelineId > ?;").use { stmt ->
									stmt.setString(1, roomId)
									stmt.setInt(2, timelineId)
									stmt.executeUpdate()
								}
							}
						} else {
							val stateEvents = response.state
							if (stateEvents != null) {
								for (event in stateEvents) {
									eventStmt.setString(1, roomId)
									eventStmt.setString(2, event.eventId)
									eventStmt.setString(3, event.type)
									eventStmt.setString(4, event.content.toString())
									eventStmt.setString(5, event.sender)
									eventStmt.setString(6, event.stateKey)
									eventStmt.setString(7, event.prevContent?.toString())
									eventStmt.setLong(8, event.originServerTimestamp)
									eventStmt.setSerializable(9, UnsignedData.serializer(), event.unsigned)
									eventStmt.setInt(10, timelineId)
									eventStmt.setNull(11, Types.INTEGER)
									val changes = eventStmt.executeUpdate()
									if (changes == 0) {
										// If this state event already existed, then server is not compliant.
										break
									}
								}
							}
							if (response.end != null) {
								conn.prepareStatement("INSERT INTO room_pagination_tokens(roomId, eventId, token) VALUES (?, ?, ?);").use { stmt ->
									stmt.setString(1, roomId)
									stmt.setString(2, newEvents.last().eventId)
									stmt.setString(3, response.end)
									stmt.executeUpdate()
								}
							}
						}
					} finally {
						eventStmt.close()
						shiftTimelineStmt.close()
					}
					conn.commit()
				}
			}
		}
	}
}
