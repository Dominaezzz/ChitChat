package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.db.*
import java.io.Closeable
import java.sql.Connection
import java.sql.Types

class SQLiteSyncStore(
	private val dbSemaphore: Semaphore
) : SyncStore {
	private suspend inline fun <T> usingReadConnection(crossinline block: (Connection) -> T): T {
		return withContext(Dispatchers.IO) {
			usingConnection { conn ->
				block(conn)
			}
		}
	}
	private suspend inline fun <T> usingWriteConnection(crossinline block: (Connection) -> T): T {
		return dbSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					conn.autoCommit = false
					block(conn)
				}
			}
		}
	}

	override suspend fun getSyncToken(): String? {
		return usingReadConnection {
			it.getValue("SYNC_TOKEN")
		}
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
			INSERT INTO room_pagination_tokens(roomId, eventId, token)
			VALUES (?, ?, ?);
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

	override suspend fun storeSync(sync: SyncResponse, token: String?) {
		usingWriteConnection { conn ->
			// Ensure sync token hasn't changed while we calling the endpoint.
			// May happen if multiple instances of app are running.
			if (token != conn.getValue("SYNC_TOKEN")) {
				println("Race condition when syncing")
				return@usingWriteConnection
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
						utils.resetTrackedUser(userId, token)
					}
					for (userId in deviceLists.left) {
						utils.deleteTrackedUser(userId)
					}
				}
			}

			conn.commit()
		}
	}

	override suspend fun getJoinedRooms(userId: String): Set<String> {
		return usingReadConnection { conn ->
			val query = """
				SELECT room_metadata.roomId
				FROM room_metadata
				JOIN room_events state
				  ON room_metadata.roomId = state.roomId AND isLatestState
				   AND type = 'm.room.member' AND stateKey = ?
				   AND JSON_EXTRACT(content, '${'$'}.membership') = 'join';
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, userId)
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildSet {
						while (rs.next()) {
							add(rs.getString(1))
						}
					}
				}
			}
		}
	}

	override suspend fun getAccountData(type: String): JsonObject? {
		return usingReadConnection { conn ->
			val query = "SELECT content FROM account_data WHERE roomId IS NULL AND type = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, type)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1, JsonObject.serializer())
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun getUserDevice(userId: String, deviceId: String): Pair<DeviceKeys?, Boolean>? {
		return usingReadConnection { conn ->
			val query = """
					SELECT json, isOutdated
					FROM tracked_users
					LEFT JOIN device_list dl ON tracked_users.userId = dl.userId AND dl.deviceId = ?
					WHERE tracked_users.userId = ?
				"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, userId)
				stmt.setString(2, deviceId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						val deviceKeys = rs.getSerializable(1, DeviceKeys.serializer())
						val isOutdated = rs.getBoolean(2)
						deviceKeys to isOutdated
					} else {
						null // no user
					}
				}
			}
		}
	}

	override suspend fun getUserDevices(userId: String): Pair<List<DeviceKeys>, Boolean>? {
		return usingReadConnection { conn ->
			val query = """
					SELECT JSON_GROUP_ARRAY(JSON(json)) FILTER (WHERE dl.userId IS NOT NULL), isOutdated
					FROM tracked_users
					LEFT JOIN device_list dl USING (userId)
					WHERE userId = ?
				"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, userId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						val deviceKeys = rs.getSerializable(1, ListSerializer(DeviceKeys.serializer()))!!
						val isOutdated = rs.getBoolean(2)
						deviceKeys to isOutdated
					} else {
						null // no user
					}
				}
			}
		}
	}


	override suspend fun getState(roomId: String, type: String, stateKey: String): JsonObject? {
		return usingReadConnection { conn ->
			val query = "SELECT content FROM room_events WHERE roomId = ? AND type = ? AND stateKey = ? AND isLatestState;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, type)
				stmt.setString(3, stateKey)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1, JsonObject.serializer())
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun getAccountData(roomId: String, type: String): JsonObject? {
		return usingReadConnection { conn ->
			val query = "SELECT content FROM account_data WHERE roomId = ? AND type = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, type)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1, JsonObject.serializer())
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun getMembers(roomId: String, membership: Membership): Set<String> {
		return usingReadConnection { conn ->
			val query = """
					SELECT stateKey
					FROM room_events
					WHERE roomId = ? AND type = 'm.room.member' AND JSON_EXTRACT(content, '${'$'}.membership') = ?
					   AND isLatestState
				"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, membership.name.toLowerCase())
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildSet<String> {
						while (rs.next()) {
							add(rs.getString(1))
						}
					}
				}
			}
		}
	}

	override suspend fun getReadReceipts(roomId: String): List<SyncStore.ReadReceipt> {
		return usingReadConnection { conn ->
			val query = """
				SELECT userId, eventId, content
				FROM room_receipts
				WHERE roomId = ? AND type = ?;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, "m.read")
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildList {
						while (rs.next()) {
							add(
								SyncStore.ReadReceipt(
									rs.getString(1),
									rs.getString(2),
									rs.getSerializable(3, ReceiptContent.Receipt.serializer())!!
								)
							)
						}
					}
				}
			}
		}
	}
}
