package me.dominaezzz.chitchat

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Direction
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import me.dominaezzz.chitchat.db.*
import java.sql.Types

class AppViewModel(
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore
) {
	private val _lastSync = MutableStateFlow<SyncResponse?>(null)
	val lastSync: StateFlow<SyncResponse?> get() = _lastSync

	class Room(
		val id: String,
		val displayName: String,
		val topic: String?,
		val memberCount: Int,
		val avatarUrl: String?
	)
	private val _rooms = MutableStateFlow<List<Room>>(emptyList())
	val rooms: StateFlow<List<Room>> get() = _rooms

	suspend fun sync() {
		val syncToken = withContext(Dispatchers.IO) {
			usingConnection { it.getValue("SYNC_TOKEN") }
		}

		println("Syncing with '$syncToken' as token")
		val sync = client.eventApi.sync(since = syncToken, setPresence = Presence.OFFLINE, timeout = 100000)
		println("Saving sync response")

		dbSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					conn.autoCommit = false

					// Ensure sync token hasn't changed while we calling the endpoint.
					// May happen if multiple instances of app are running.
					if (syncToken != conn.getValue("SYNC_TOKEN")) {
						println("Race condition when syncing")
						return@usingConnection
					}

					conn.setValue("SYNC_TOKEN", sync.nextBatch)

					val rooms = sync.rooms
					if (rooms != null) {
						val joinedRooms = rooms.join
						if (joinedRooms.isNotEmpty()) {
							val roomStmt = conn.prepareStatement("""
                                INSERT INTO room_metadata(roomId, summary)
                                VALUES (?, ?)
                                ON CONFLICT(roomId) DO UPDATE SET summary=JSON_PATCH(summary, excluded.summary);
                            """)
							val eventStmt = conn.prepareStatement("""
                                INSERT INTO room_events(
                                    roomId, eventId, type, content, sender, unsigned, stateKey, prevContent, timestamp,
                                    timelineOrder
                                )
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT(roomId, eventId) DO UPDATE
                                    SET timelineOrder = excluded.timelineOrder
                                    WHERE excluded.timelineOrder IS NOT NULL;
                            """)
							val getLastOrderStmt = conn.prepareStatement("SELECT MAX(timelineOrder) FROM room_events WHERE roomId = ? AND timelineId = 0;")
							val paginationTokenStmt = conn.prepareStatement("INSERT INTO room_pagination_tokens(roomId, eventId, token) VALUES (?, ?, ?);")
							val newTimelineStmt = conn.prepareStatement("UPDATE room_events SET timelineId = timelineId + 1 WHERE roomId = ?;")
							val accountStmt = conn.prepareStatement("INSERT OR REPLACE INTO account_data(type, roomId, content) VALUES (?, ?, ?);")
							for ((roomId, joinedRoom) in joinedRooms) {
								val summary = joinedRoom.summary
								if (summary != null) {
									roomStmt.setString(1, roomId)
									roomStmt.setString(2, MatrixJson.encodeToString(RoomSummary.serializer(), summary))
									roomStmt.executeUpdate()
								}

								val timeline = joinedRoom.timeline
								if (timeline != null) {
									// Create new batch
									var order = if (timeline.limited == true) {
										newTimelineStmt.setString(1, roomId)
										newTimelineStmt.executeUpdate()
										1
									} else {
										getLastOrderStmt.setString(1, roomId)
										getLastOrderStmt.executeQuery().use {
											check(it.next())
											it.getLong(1) + 1
										}
									}
									for (event in timeline.events) {
										eventStmt.setString(1, roomId)
										eventStmt.setString(2, event.eventId)
										eventStmt.setString(3, event.type)
										eventStmt.setString(4, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
										eventStmt.setString(5, event.sender)
										eventStmt.setString(6, event.unsigned?.let { MatrixJson.encodeToString(
											UnsignedData.serializer(), it) })
										eventStmt.setString(7, event.stateKey)
										eventStmt.setString(8, event.prevContent?.let { MatrixJson.encodeToString(
											JsonElement.serializer(), it) })
										eventStmt.setLong(9, event.originServerTimestamp)
										eventStmt.setLong(10, order++)
										eventStmt.executeUpdate()
									}
									if (timeline.limited == true) {
										paginationTokenStmt.setString(1, roomId)
										paginationTokenStmt.setString(2, timeline.events.first().eventId)
										paginationTokenStmt.setString(3, timeline.prevBatch!!)
										paginationTokenStmt.executeUpdate()
									}
								}

								val stateEvents = joinedRoom.state?.events
								if (stateEvents != null) {
									for (event in stateEvents) {
										eventStmt.setString(1, roomId)
										eventStmt.setString(2, event.eventId)
										eventStmt.setString(3, event.type)
										eventStmt.setString(4, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
										eventStmt.setString(5, event.sender)
										eventStmt.setString(6, event.unsigned?.let { MatrixJson.encodeToString(
											UnsignedData.serializer(), it) })
										eventStmt.setString(7, event.stateKey)
										eventStmt.setString(8, event.prevContent?.let { MatrixJson.encodeToString(
											JsonElement.serializer(), it) })
										eventStmt.setLong(9, event.originServerTimestamp)
										eventStmt.setNull(10, Types.INTEGER)
										eventStmt.executeUpdate()
									}
								}

								val accountEvents = joinedRoom.accountData?.events
								if (accountEvents != null) {
									for (event in accountEvents) {
										accountStmt.setString(1, event.type)
										accountStmt.setString(2, roomId)
										accountStmt.setString(3, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
										accountStmt.executeUpdate()
									}
								}
							}
							accountStmt.close()
							newTimelineStmt.close()
							paginationTokenStmt.close()
							getLastOrderStmt.close()
							eventStmt.close()
							roomStmt.close()
						}
					}

					val accountData = sync.accountData
					if (accountData != null && accountData.events.isNotEmpty()) {
						conn.prepareStatement(
							"""
                            INSERT OR REPLACE INTO account_data(type, roomId, content)
                            VALUES (?, NULL, ?);
                            """).use { stmt ->
							for (event in accountData.events) {
								stmt.setString(1, event.type)
								stmt.setString(2, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
								stmt.executeUpdate()
							}
						}
					}

					val deviceEvents = sync.toDevice?.events
					if (deviceEvents != null) {
						conn.prepareStatement("INSERT INTO device_events(type, content, sender) VALUES (?, ?, ?);").use { stmt ->
							for (event in deviceEvents) {
								stmt.setString(1, event.type)
								stmt.setString(2, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
								stmt.setString(3, event.sender)
								stmt.executeUpdate()
							}
						}
					}

					val deviceLists = sync.deviceLists
					if (deviceLists != null) {
						conn.prepareStatement("UPDATE tracked_users SET deviceListState = 0 WHERE userId = ?;").use { stmt ->
							for (userId in deviceLists.changed) {
								stmt.setString(1, userId)
								stmt.executeUpdate()
							}
						}
						// conn.prepareStatement("DELETE FROM tracked_users WHERE userId = ?;").use { stmt ->
						//     for (userId in deviceLists.left) {
						//         stmt.setString(1, userId)
						//         val updateCount = stmt.executeUpdate()
						//         if (updateCount == 0) {
						//             println("User with id $userId left but we were not tracking them...")
						//         }
						//     }
						// }
					}

					conn.commit()
				}
			}
		}

		_lastSync.value = sync
	}

	suspend fun rooms(rooms: SnapshotStateList<Room>) {
		rooms.clear()
		val initialRooms = loadRoomsFromDatabase()
		rooms.addAll(initialRooms)
		lastSync.mapNotNull { it?.rooms?.join }
			.cancellable()
			.collect { joinedRooms ->
				val missingRooms = joinedRooms.keys.toMutableSet()
				rooms.forEach { missingRooms.remove(it.id) }
				if (missingRooms.isNotEmpty()) {
					// TODO: Make this smarter/granular.
					rooms.clear()
					rooms.addAll(loadRoomsFromDatabase())
				}
			}
	}

	@OptIn(ExperimentalStdlibApi::class)
	private suspend fun loadRoomsFromDatabase(): List<Room> {
		return withContext(Dispatchers.IO) {
			usingStatement { stmt ->
				stmt.executeQuery(ROOM_INFO_SQL).use { rs ->
					val idIndex = rs.findColumn("roomId")
					val nameIndex = rs.findColumn("displayName")
					val avatarIndex = rs.findColumn("displayAvatar")
					val mCountIndex = rs.findColumn("memberCount")
					val topicIndex = rs.findColumn("topic")

					buildList {
						while (rs.next()) {
							add(Room(
								id = rs.getString(idIndex),
								displayName = rs.getString(nameIndex),
								topic = rs.getString(topicIndex),
								avatarUrl = rs.getString(avatarIndex),
								memberCount = rs.getInt(mCountIndex)
							))
						}
					}
				}
			}
		}
	}

	suspend fun selectRoom(
		roomId: String,
		timelineEvents: SnapshotStateList<TimelineItem>,
		relevantMembers: SnapshotStateMap<String, MemberContent>,
		shouldBackPaginate: StateFlow<Boolean>
	) {
		timelineEvents.clear()
		relevantMembers.clear()

		// Initial load
		run {
			val events: List<TimelineItem>
			val members: List<Pair<String, MemberContent>>
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					events = conn.getEventsBetween(roomId, 1, Int.MAX_VALUE)
					members = conn.getRelevantMembersBetween(roomId, 1, Int.MAX_VALUE)
				}
			}
			timelineEvents.addAll(events)
			relevantMembers.putAll(members)
		}

		// Load future events
		val futureEventsFlow = _lastSync.mapNotNull { it?.rooms?.join?.get(roomId) }
			.filter { it.timeline?.events.isNullOrEmpty() }
			.onEach {
				val item = timelineEvents.last()
				val lastEvent = item.event
				val eventId = lastEvent.eventId

				val events: List<TimelineItem>
				val members: List<Pair<String, MemberContent>>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(roomId, eventId)
						if (timelineId != 0) {
							TODO("Our timeline was disconnected from the latest timeline. RIP.")
							// Probably best to clear everything and start again in
							// this case or attempt to restitch the separate timelines.
						}

						events = conn.getEventsBetween(roomId, timelineOrder + 1, Int.MAX_VALUE)
						ensureActive()
						members = conn.getRelevantMembersBetween(roomId, timelineOrder + 1, Int.MAX_VALUE)
					}
				}

				timelineEvents.addAll(events)
				relevantMembers.putAll(members)
			}

		// Load past events
		val pastEventsFlow = shouldBackPaginate.filter { it }
			.conflate()
			.onEach {
				// TODO: What if this is empty though... like if all the events in
				// the database are not supported.
				val item = timelineEvents.first()
				val targetEvent = item.event
				val eventId = targetEvent.eventId

				println("Back paginating")
				backFill(roomId, eventId)
				println("Past events downloaded")

				var events: List<TimelineItem>
				var members: List<Pair<String, MemberContent>>
				withContext(Dispatchers.IO) {
					usingConnection { conn ->
						val (timelineId, timelineOrder) = conn.getTimelineIdAndOrder(roomId, eventId)
						if (timelineId != 0) { TODO("The timeline we just back-filled was disconnected from the latest timeline. RIP.") }

						events = conn.getEventsBetween(roomId, 1, timelineOrder - 1)
						ensureActive()
						members = conn.getRelevantMembersBetween(roomId, 1, timelineOrder - 1)
					}
				}
				timelineEvents.addAll(0, events)
				for ((member, content) in members) {
					relevantMembers.putIfAbsent(member, content)
				}
			}

		coroutineScope {
			futureEventsFlow.launchIn(this)
			pastEventsFlow.launchIn(this)
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
								// If no rows were inserted, then there was a conflict between two timeline events.
								// which means we have to stitch two timeline chunks together.
								break
								// By just breaking here, we assume we already have all the skipped events stored in earlier timeline.
							}
							timelineOrder--
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
							conn.prepareStatement("UPDATE room_events SET timelineId = timelineId - 1 WHERE roomId = ? AND timelineId > ?;").use { stmt ->
								stmt.setString(1, roomId)
								stmt.setInt(2, timelineId)
								stmt.executeUpdate()
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
