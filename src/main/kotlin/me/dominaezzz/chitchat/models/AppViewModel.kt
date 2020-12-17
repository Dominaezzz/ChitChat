package me.dominaezzz.chitchat.models

import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import me.dominaezzz.chitchat.db.*
import java.sql.Types

class AppViewModel(
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore
) {
	private val _syncFlow = MutableSharedFlow<SyncResponse>()

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

		_syncFlow.emit(sync)
	}

	suspend fun rooms(rooms: SnapshotStateList<RoomHeader>) {
		rooms.clear()

		val initialRooms = loadRoomsFromDatabase(emptyList())
		rooms.addAll(initialRooms)

		_syncFlow.mapNotNull { it.rooms?.join }
			.filter { joinedRooms ->
				joinedRooms.values.asSequence()
					.mapNotNull { it.timeline }
					.flatMap { it.events }
					.any { it.stateKey != null } ||
						joinedRooms.values.any { it.state != null }
			}
			.map { Unit }
			.conflate()
			.collect {
				val changedRooms = loadRoomsFromDatabase(rooms)
				val lookUpMap = changedRooms.associateByTo(mutableMapOf()) { it.id }

				for (i in rooms.indices) {
					val changedRoom = lookUpMap.remove(rooms[i].id)
					if (changedRoom != null) {
						rooms[i] = changedRoom
					}
				}

				if (lookUpMap.isNotEmpty()) {
					// Might want to consider inserting this to
					// preserve some sort of ordering.
					rooms.addAll(lookUpMap.values)
				}
			}
	}

	@OptIn(ExperimentalStdlibApi::class)
	private suspend fun loadRoomsFromDatabase(loadedRooms: List<RoomHeader>): List<RoomHeader> {
		val loadedRoomsJson = buildJsonObject {
			for (room in loadedRooms) {
				putJsonObject(room.id) {
					put("first_events_id", room.firstEventsId)
					put("last_state_order", room.prevLatestOrder)
				}
			}
		}
		return withContext(Dispatchers.IO) {
			usingConnection { conn ->
				conn.prepareStatement(ROOM_INFO_SQL).use { stmt ->
					stmt.setSerializable(1, JsonElement.serializer(), loadedRoomsJson)

					stmt.executeQuery().use { rs ->
						buildList {
							while (rs.next()) {
								add(
									RoomHeader(
										id = rs.getString(1),
										displayName = rs.getString(2),
										avatarUrl = rs.getString(3),
										memberCount = rs.getInt(4),
										topic = rs.getString(5),
										firstEventsId = rs.getString(6),
										prevLatestOrder = rs.getInt(7)
									)
								)
							}
						}
					}
				}
			}
		}
	}

	fun getRoomTimeline(scope: CoroutineScope, roomId: String): RoomTimeline {
		val timeline = RoomTimeline(roomId, _syncFlow, client, dbSemaphore)
		scope.launch { timeline.run() }
		return timeline
	}
}
