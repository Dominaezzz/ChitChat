package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.GetMembersResponse
import io.github.matrixkt.models.MessagesResponse
import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.UnsignedData
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.StrippedState
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
			INSERT INTO room_metadata(roomId, summary, loadedMembershipTypes)
			VALUES (?, ?, ?)
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
		private val oneTimeKeysStmt = connection.prepareStatement("""
			INSERT INTO key_value_store(key, value)
			VALUES ('ONE_TIME_KEYS_COUNT', ?)
			ON CONFLICT(key) DO UPDATE SET value=JSON_PATCH(value, excluded.value);
		""")
		private val insertInviteState = connection.prepareStatement("""
			INSERT OR REPLACE INTO room_invitations(roomId, type, stateKey, sender, content)
			VALUES (?, ?, ?, ?, ?);
		""")
		private val clearInviteState = connection.prepareStatement("DELETE FROM room_invitations WHERE roomId = ?;")

		fun updateRoomSummary(roomId: String, summary: RoomSummary): Int {
			roomSummaryStmt.setString(1, roomId)
			roomSummaryStmt.setString(2, MatrixJson.encodeToString(RoomSummary.serializer(), summary))
			roomSummaryStmt.setSerializable(3, ListSerializer(Membership.serializer()), Membership.values().asList())
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

		fun updateOneTimeKeys(oneTimeKeysCount: Map<String, Long>) {
			oneTimeKeysStmt.setSerializable(1, MapSerializer(String.serializer(), Long.serializer()), oneTimeKeysCount)
			oneTimeKeysStmt.executeUpdate()
		}

		fun insertInviteRoomState(roomId: String, state: StrippedState) {
			insertInviteState.setString(1, roomId)
			insertInviteState.setString(2, state.type)
			insertInviteState.setString(3, state.stateKey)
			insertInviteState.setString(4, state.sender)
			insertInviteState.setSerializable(5, JsonObject.serializer(), state.content)
			insertInviteState.executeUpdate()
		}

		fun clearInvitedState(roomId: String) {
			clearInviteState.setString(1, roomId)
			clearInviteState.executeUpdate()
		}

		override fun close() {
			clearInviteState.close()
			insertInviteState.close()
			oneTimeKeysStmt.close()
			updateReceipt.close()
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

								val stateEvents = joinedRoom.state?.events
								if (stateEvents != null) {
									for (event in stateEvents) {
										utils.insertRoomEvent(roomId, event, null)
									}
								}
								for (event in timeline.events) {
									utils.insertRoomEvent(roomId, event, order++)
								}
								if (timeline.limited == true) {
									utils.setPaginationToken(roomId, timeline.events.first().eventId, timeline.prevBatch!!)
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

					for (roomId in (rooms.leave.keys + rooms.join.keys)) {
						utils.clearInvitedState(roomId)
					}
					for ((roomId, invitedRoom) in rooms.invite) {
						val state = invitedRoom.inviteState
						if (state != null) {
							for (event in state.events.orEmpty()) {
								utils.insertInviteRoomState(roomId, event)
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

				val oneTimeKeysCount = sync.deviceOneTimeKeysCount
				if (oneTimeKeysCount != null) {
					utils.updateOneTimeKeys(oneTimeKeysCount)
				}
			}

			conn.commit()
		}
	}

	override suspend fun getOneTimeKeysCount(): Map<String, Long> {
		return usingReadConnection { conn ->
			val count = conn.getValue("ONE_TIME_KEYS_COUNT")
			if (count != null) {
				Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), count)
			} else {
				emptyMap()
			}
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

	override suspend fun getInvitations(): Map<String, List<StrippedState>> {
		return usingReadConnection { conn ->
			val query = """
				SELECT JSON_OBJECT(roomId, JSON_GROUP_ARRAY(JSON_OBJECT(
					'type', type,
					'state_key', stateKey,
					'sender', sender,
					'content', JSON(content)
				)))
				FROM room_invitations
				GROUP BY roomId;
			"""
			conn.usingStatement { stmt ->
				stmt.executeQuery(query).use { rs ->
					if (rs.next()) {
						rs.getSerializable(1, MapSerializer(String.serializer(), ListSerializer(StrippedState.serializer())))!!
					} else {
						emptyMap()
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

	override suspend fun getSummary(roomId: String): RoomSummary {
		return usingReadConnection { conn ->
			val query = "SELECT summary FROM room_metadata WHERE roomId = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1, RoomSummary.serializer())!!
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}
		}
	}

	override suspend fun getLazyLoadingState(roomId: String): SyncStore.LazyLoadingState {
		return usingReadConnection { conn ->
			val query = """
				WITH
					room_event_idx(roomId, eventId, idx) AS (
						SELECT roomId, eventId, ROW_NUMBER() OVER (PARTITION BY roomId ORDER BY timelineId DESC, timelineOrder)
						FROM room_events
						WHERE timelineOrder IS NOT NULL
					),
					room_tokens(roomId, token) AS (
					    SELECT roomId, token
						FROM room_pagination_tokens
						JOIN room_event_idx USING(roomId, eventId)
						WHERE idx = 0
					)
				SELECT token, loadedMembershipTypes
				FROM room_metadata
				LEFT JOIN room_tokens USING (roomId) 
				WHERE roomId = ?;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						SyncStore.LazyLoadingState(
							rs.getString(1),
							rs.getSerializable(2, SetSerializer(Membership.serializer()))!!
						)
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}
		}
	}

	private fun Connection.getNewState(roomId: String, eventIds: Set<String>): List<MatrixEvent> {
		val queryNewState = """
				WITH new_events(eventId) AS (SELECT value FROM JSON_EACH(?2))
				SELECT JSON_GROUP_ARRAY(JSON_OBJECT(
					 'type', type,
					 'content', JSON(content),
					 'event_id', eventId,
					 'sender', sender,
					 'origin_server_ts', timestamp,
					 'unsigned', JSON(unsigned),
					 'room_id', roomId,
					 'state_key', stateKey,
					 'prev_content', JSON(prevContent)
				 ))
				FROM room_events
				JOIN new_events USING(eventId)
				WHERE roomId = ?1 AND isLatestState
			"""
		return prepareStatement(queryNewState).use { stmt ->
			stmt.setString(1, roomId)
			stmt.setSerializable(2, SetSerializer(String.serializer()), eventIds)
			stmt.executeQuery().use { rs ->
				check(rs.next())
				rs.getSerializable(1, ListSerializer(MatrixEvent.serializer()))!!
			}
		}
	}

	override suspend fun storeMembers(roomId: String, response: GetMembersResponse): List<MatrixEvent> {
		return usingWriteConnection { conn ->
			val query = "SELECT MAX(timelineId) FROM room_events WHERE roomId = ?;"
			val oldestTimelineId = conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getInt(1)
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}

			val insertedEventIds = mutableSetOf<String>()

			val insert = """
				INSERT OR IGNORE INTO room_events(roomId, eventId, type, content, sender, timestamp, unsigned, stateKey, prevContent, timelineId, timelineOrder)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL);
			"""
			conn.prepareStatement(insert).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setInt(10, oldestTimelineId)

				for (event in response.chunk) {
					require(event.roomId == roomId)
					require(event.type == "m.room.member")

					stmt.setString(2, event.eventId)
					stmt.setString(3, event.type)
					stmt.setSerializable(4, MemberContent.serializer(), event.content)
					stmt.setString(5, event.sender)
					stmt.setLong(6, event.originServerTimestamp)
					stmt.setSerializable(7, UnsignedData.serializer(), event.unsigned)
					stmt.setString(8, event.stateKey!!)
					stmt.setSerializable(9, MemberContent.serializer(), event.prevContent)
					val changes = stmt.executeUpdate()
					if (changes > 0) {
						insertedEventIds.add(event.eventId)
					}
				}
			}

			val update = """
				UPDATE room_metadata
				SET loadedMembershipTypes = (
					SELECT JSON_GROUP_ARRAY(value)
					FROM (
						SELECT value FROM JSON_EACH(loadedMembershipTypes)
						UNION
						SELECT value FROM JSON_EACH(?2)
					)
				)
				WHERE roomId = ?1
			"""
			conn.prepareStatement(update).use { stmt ->
				val memberships = response.chunk.asSequence()
					.map { it.content.membership }.toSet()

				stmt.setString(1, roomId)
				stmt.setSerializable(2, SetSerializer(Membership.serializer()), memberships)
				stmt.executeUpdate()
			}

			val newState = conn.getNewState(roomId, insertedEventIds)

			conn.commit()

			newState
		}
	}


	override suspend fun getPaginationToken(roomId: String, eventId: String): String? {
		return usingReadConnection { conn ->
			// Find first event in timeline and get corresponding token.
			val query = """
				SELECT token
				FROM room_pagination_tokens
				JOIN room_events head_events USING(roomId, eventId)
				JOIN room_events tail_events USING(roomId, timelineId)
				WHERE roomId = ? AND tail_events.eventId = ?;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, eventId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getString(1)
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun storeTimelineEvents(roomId: String, response: MessagesResponse): List<MatrixEvent> {
		// BUG: Overlap in state and timeline events adds offset to timelineOrder.
		// BUG: Pagination token is stored against the first timeline event in the response,
		//  which may have been downgraded to state due to above.

		val timelineEvents = response.chunk
		if (timelineEvents.isNullOrEmpty()) {
			// check(response.state.isNullOrEmpty())
			return emptyList()
		}

		return usingWriteConnection { conn ->
			val getEventIdQuery = "SELECT eventId FROM room_pagination_tokens WHERE roomId = ? AND token = ?"
			val eventId = conn.prepareStatement(getEventIdQuery).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, response.start)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getString(1)
					} else {
						throw NoSuchElementException(
							"Pagination token '${response.start}' not in Room($roomId) timelines.")
					}
				}
			}

			conn.prepareStatement("DELETE FROM room_pagination_tokens WHERE roomId = ? AND eventId = ?;").use {
				it.setString(1, roomId)
				it.setString(2, eventId)
				val changes = it.executeUpdate()
				check(changes == 1)
			}

			val timelineId = run {
				val (id, order) = conn.getTimelineIdAndOrder(roomId, eventId)
				check(order == 1) { "Edge of timeline should be at idx 1 but was at idx $order." }
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

			val insertedEventIds = mutableSetOf<String>()
			try {
				shiftTimelineStmt.setInt(1, timelineEvents.size)
				shiftTimelineStmt.executeUpdate()

				var timelineOrder = timelineEvents.size
				var overlappingEvents = 0 // kludge for when synapse returns event out of bounds.

				for (event in timelineEvents) {
					eventStmt.setString(1, roomId)
					eventStmt.setString(2, event.eventId)
					eventStmt.setString(3, event.type)
					eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
					eventStmt.setString(5, event.sender)
					eventStmt.setString(6, event.stateKey)
					eventStmt.setSerializable(7, JsonElement.serializer(), event.prevContent)
					eventStmt.setLong(8, event.originServerTimestamp)
					eventStmt.setSerializable(9, JsonElement.serializer(), event.unsigned)
					eventStmt.setInt(10, timelineId)
					eventStmt.setInt(11, timelineOrder)
					val changes = eventStmt.executeUpdate()
					if (changes == 0) {
						val (id, _) = conn.getTimelineIdAndOrder(roomId, event.eventId)
						// If server returned overlapping event ... we skip it.
						if (id == timelineId) {
							check(timelineEvents.size == (timelineOrder - overlappingEvents)) {
								"Server returned inconsistent duplicate message events!"
							}
							overlappingEvents++
							continue
						}

						// If no rows were inserted, then there was a conflict between two timeline events.
						// which means we have to stitch two timeline chunks together.
						break
						// By just breaking here, we assume we already have all the skipped events stored in earlier timeline.
					} else {
						insertedEventIds.add(event.eventId)
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
							eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
							eventStmt.setString(5, event.sender)
							eventStmt.setString(6, event.stateKey)
							eventStmt.setSerializable(7, JsonElement.serializer(), event.prevContent)
							eventStmt.setLong(8, event.originServerTimestamp)
							eventStmt.setSerializable(9, JsonElement.serializer(), event.unsigned)
							eventStmt.setInt(10, timelineId)
							eventStmt.setNull(11, Types.INTEGER)
							val changes = eventStmt.executeUpdate()
							if (changes > 0) {
								insertedEventIds.add(event.eventId)
							}
						}
					}
					if (response.end != null) {
						conn.prepareStatement("INSERT INTO room_pagination_tokens(roomId, eventId, token) VALUES (?, ?, ?);").use { stmt ->
							stmt.setString(1, roomId)
							stmt.setString(2, timelineEvents.last().eventId)
							stmt.setString(3, response.end)
							stmt.executeUpdate()
						}
					}
				}
			} finally {
				eventStmt.close()
				shiftTimelineStmt.close()
			}

			val newState = conn.getNewState(roomId, insertedEventIds)

			conn.commit()

			newState
		}
	}

	suspend fun clear() {
		usingWriteConnection { conn ->
			conn.usingStatement { stmt ->
				stmt.execute("DELETE FROM key_value_store WHERE key = 'SYNC_TOKEN';")
				stmt.execute("DELETE FROM key_value_store WHERE key = 'ONE_TIME_KEYS_COUNT';")
				stmt.execute("DELETE FROM room_metadata;")
				stmt.execute("DELETE FROM room_events;")
				stmt.execute("DELETE FROM room_pagination_tokens;")
				stmt.execute("DELETE FROM account_data;")
				stmt.execute("DELETE FROM room_receipts;")
				stmt.execute("UPDATE tracked_users SET isOutdated = TRUE, sync_token = NULL;")
			}
		}
	}
}
