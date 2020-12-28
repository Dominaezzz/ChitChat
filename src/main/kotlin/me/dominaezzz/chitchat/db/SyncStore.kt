package me.dominaezzz.chitchat.db

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.sql.Connection
import java.sql.Types


suspend fun sync(client: MatrixClient, dbSemaphore: Semaphore): SyncResponse {
	val syncToken = withContext(Dispatchers.IO) {
		usingConnection { it.getValue("SYNC_TOKEN") }
	}

	println("Syncing with '$syncToken' as token")

	val sync = client.eventApi.sync(since = syncToken, setPresence = Presence.OFFLINE, timeout = 100000)

	println("Saving sync response")

	dbSemaphore.withPermit {
		withContext(Dispatchers.IO) {
			storeSyncResponse(sync, syncToken)
		}
	}

	println("Saved sync response")

	return sync
}

private class InsertUtils(connection: Connection): Closeable {
	private val roomSummaryStmt = connection.prepareStatement("""
		INSERT INTO room_metadata(roomId, summary)
		VALUES (?, ?)
		ON CONFLICT(roomId) DO UPDATE SET summary=JSON_PATCH(summary, excluded.summary);
	""")
	private val eventStmt = connection.prepareStatement("""
		INSERT INTO room_events(
			roomId, eventId, type, content, sender, unsigned, stateKey, prevContent, timestamp,
			timelineOrder
		)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(roomId, eventId) DO UPDATE
			SET timelineOrder = excluded.timelineOrder
			WHERE excluded.timelineOrder IS NOT NULL;
	""")
	private val paginationTokenStmt = connection.prepareStatement("""
		INSERT INTO room_pagination_tokens(roomId, eventId, token) VALUES (?, ?, ?);
	""")
	private val accountStmt = connection.prepareStatement("""
		INSERT OR REPLACE INTO account_data(type, roomId, content) VALUES (?, ?, ?);
	""")
	private val lastOrderStmt = connection.prepareStatement("""
		SELECT MAX(timelineOrder) FROM room_events WHERE roomId = ? AND timelineId = 0;
	""")
	private val newTimelineStmt = connection.prepareStatement("UPDATE room_events SET timelineId = timelineId + 1 WHERE roomId = ?;")
	private val deviceEventStmt = connection.prepareStatement("INSERT INTO device_events(type, content, sender) VALUES (?, ?, ?);")
	private val trackedUsersStmt = connection.prepareStatement("UPDATE tracked_users SET isOutdated = TRUE, sync_token = ? WHERE userId = ?;")
	private val removeTrackedUserStmt = connection.prepareStatement("DELETE FROM tracked_users WHERE userId = ?;")
	private val updateReceipt = connection.prepareStatement("""
		INSERT OR REPLACE INTO room_receipts(roomId, userId, type, eventId, content)
		VALUES (?, ?, ?, ?, ?); 
	""")

	fun updateRoomSummary(roomId: String, summary: RoomSummary): Int {
		roomSummaryStmt.setString(1, roomId)
		roomSummaryStmt.setString(2, MatrixJson.encodeToString(RoomSummary.serializer(), summary))
		return roomSummaryStmt.executeUpdate()
	}

	fun insertRoomEvent(roomId: String, event: MatrixEvent, timelineOrder: Long?): Int {
		eventStmt.setString(1, roomId)
		eventStmt.setString(2, event.eventId)
		eventStmt.setString(3, event.type)
		eventStmt.setString(4, MatrixJson.encodeToString(JsonElement.serializer(), event.content))
		eventStmt.setString(5, event.sender)
		eventStmt.setString(6, event.unsigned?.let { MatrixJson.encodeToString(
			JsonElement.serializer(), it) })
		eventStmt.setString(7, event.stateKey)
		eventStmt.setString(8, event.prevContent?.let { MatrixJson.encodeToString(
			JsonElement.serializer(), it) })
		eventStmt.setLong(9, event.originServerTimestamp)
		if (timelineOrder != null) {
			eventStmt.setLong(10, timelineOrder)
		} else {
			eventStmt.setNull(10, Types.INTEGER)
		}
		return eventStmt.executeUpdate()
	}

	fun setPaginationToken(roomId: String, eventId: String, token: String): Int {
		paginationTokenStmt.setString(1, roomId)
		paginationTokenStmt.setString(2, eventId)
		paginationTokenStmt.setString(3, token)
		return paginationTokenStmt.executeUpdate()
	}

	fun updateAccountData(roomId: String?, type: String, content: JsonElement): Int {
		accountStmt.setString(1, type)
		accountStmt.setString(2, roomId)
		accountStmt.setString(3, MatrixJson.encodeToString(JsonElement.serializer(), content))
		return accountStmt.executeUpdate()
	}

	fun getLastOrder(roomId: String): Long {
		lastOrderStmt.setString(1, roomId)
		return lastOrderStmt.executeQuery().use { rs ->
			check(rs.next())
			rs.getLong(1)
		}
	}

	fun createNewTimeline(roomId: String) {
		newTimelineStmt.setString(1, roomId)
		newTimelineStmt.executeUpdate()
	}

	fun insertDeviceEvent(type: String, sender: String, content: JsonElement): Int {
		deviceEventStmt.setString(1, type)
		deviceEventStmt.setString(2, MatrixJson.encodeToString(JsonElement.serializer(), content))
		deviceEventStmt.setString(3, sender)
		return deviceEventStmt.executeUpdate()
	}

	fun resetTrackedUser(userId: String, syncToken: String?): Int {
		trackedUsersStmt.setString(1, syncToken)
		trackedUsersStmt.setString(2, userId)
		return trackedUsersStmt.executeUpdate()
	}

	fun deleteTrackedUser(userId: String): Int {
		removeTrackedUserStmt.setString(1, userId)
		return removeTrackedUserStmt.executeUpdate()
	}

	fun updateReceipt(roomId: String, userId: String, type: String, eventId: String, receipt: ReceiptContent.Receipt): Int {
		updateReceipt.setString(1, roomId)
		updateReceipt.setString(2, userId)
		updateReceipt.setString(3, type)
		updateReceipt.setString(4, eventId)
		updateReceipt.setSerializable(5, ReceiptContent.Receipt.serializer(), receipt)
		return updateReceipt.executeUpdate()
	}

	override fun close() {
		removeTrackedUserStmt.close()
		trackedUsersStmt.close()
		deviceEventStmt.close()
		newTimelineStmt.close()
		lastOrderStmt.close()
		accountStmt.close()
		paginationTokenStmt.close()
		eventStmt.close()
		roomSummaryStmt.close()
	}
}

private fun storeSyncResponse(sync: SyncResponse, syncToken: String?) {
	usingConnection { conn ->
		conn.autoCommit = false

		// Ensure sync token hasn't changed while we calling the endpoint.
		// May happen if multiple instances of app are running.
		if (syncToken != conn.getValue("SYNC_TOKEN")) {
			println("Race condition when syncing")
			return@usingConnection
		}

		conn.setValue("SYNC_TOKEN", sync.nextBatch)

		InsertUtils(conn).use { utils ->
			val rooms = sync.rooms
			if (rooms != null) {
				conn.withoutIndex("room_events", "compressed_state") {
					for ((roomId, joinedRoom) in rooms.join) {
						val summary = joinedRoom.summary
						if (summary != null) {
							utils.updateRoomSummary(roomId, summary)
						}

						val timeline = joinedRoom.timeline
						if (timeline != null) {
							// Create new batch
							var order = if (timeline.limited == true) {
								utils.createNewTimeline(roomId)
								1
							} else {
								utils.getLastOrder(roomId) + 1
							}
							for (event in timeline.events) {
								utils.insertRoomEvent(roomId, event, order++)
							}
							if (timeline.limited == true) {
								utils.setPaginationToken(roomId, timeline.events.first().eventId, timeline.prevBatch!!)
							}
						}

						val stateEvents = joinedRoom.state?.events
						if (stateEvents != null) {
							for (event in stateEvents) {
								utils.insertRoomEvent(roomId, event, null)
							}
						}

						val accountEvents = joinedRoom.accountData?.events
						if (accountEvents != null) {
							for (event in accountEvents) {
								utils.updateAccountData(roomId, event.type, event.content)
							}
						}

						val ephemeral = joinedRoom.ephemeral
						if (ephemeral != null) {
							for (event in ephemeral.events) {
								if (event.type == "m.receipt") {
									val receipt = MatrixJson.decodeFromJsonElement(ReceiptContent.serializer(), event.content)
									for ((eventId, receipts) in receipt) {
										val read = receipts.read
										if (read != null) {
											for ((userId, readReceipt) in read) {
												utils.updateReceipt(roomId, userId, "m.read", eventId, readReceipt)
											}
										}
									}
								}
							}
						}
					}
				}
			}

			val accountData = sync.accountData
			if (accountData != null) {
				for (event in accountData.events) {
					utils.updateAccountData(null, event.type, event.content)
				}
			}

			val deviceEvents = sync.toDevice?.events
			if (deviceEvents != null) {
				for (event in deviceEvents) {
					utils.insertDeviceEvent(event.type, event.sender!!, event.content)
				}
			}

			val deviceLists = sync.deviceLists
			if (deviceLists != null) {
				for (userId in deviceLists.changed) {
					utils.resetTrackedUser(userId, syncToken)
				}
				for (userId in deviceLists.left) {
					utils.deleteTrackedUser(userId)
				}
			}
		}

		conn.commit()
	}
}
