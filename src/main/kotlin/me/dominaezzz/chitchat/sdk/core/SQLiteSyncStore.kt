package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.api.GetMembersByRoom
import io.github.matrixkt.api.GetRoomEvents
import io.github.matrixkt.models.events.StrippedState
import io.github.matrixkt.models.events.SyncEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.models.sync.UnreadNotificationCounts
import io.github.matrixkt.utils.MatrixJson
import kotlinx.serialization.builtins.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.sdk.util.*
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.Types

class SQLiteSyncStore(private val databaseFile: Path) : SyncStore {
	private val helper = object : SQLiteHelper(databaseFile, 1) {
		override fun onCreate(connection: Connection) {
			connection.usingStatement { stmt ->
				stmt.execute("""
					CREATE TABLE key_value_store
					(
						key   TEXT PRIMARY KEY NOT NULL,
						value TEXT
					);
				""")
				stmt.execute("""
					CREATE TABLE room_metadata
					(
						roomId                TEXT PRIMARY KEY NOT NULL,
						summary               TEXT,
						loadedMembershipTypes TEXT NOT NULL CHECK (JSON_VALID(loadedMembershipTypes)),
						unreadNotifications   TEXT NOT NULL CHECK (JSON_VALID(unreadNotifications))
					);
				""")
				stmt.execute("""
					CREATE TABLE room_timelines
					(
						roomId     TEXT    NOT NULL,
						timelineId INTEGER NOT NULL,
						token      TEXT,
					
						PRIMARY KEY (roomId, timelineId),
						FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
							ON DELETE CASCADE
					);
				""")
				stmt.execute("""
					CREATE TABLE room_events
					(
						roomId      TEXT    NOT NULL,
						eventId     TEXT    NOT NULL,
						type        TEXT    NOT NULL,
						content     TEXT    NOT NULL CHECK (JSON_VALID(content)),
						sender      TEXT    NOT NULL,
						timestamp   INTEGER NOT NULL,
						unsigned    TEXT    CHECK (unsigned IS NULL OR JSON_VALID(unsigned)),
						stateKey    TEXT,
						prevContent TEXT    CHECK (prevContent IS NULL OR JSON_VALID(prevContent)),
					
						json        TEXT GENERATED ALWAYS AS (
							JSON_OBJECT(
								'room_id', roomId,
								'event_id', eventId,
								'type', type,
								'content', JSON(content),
								'sender', sender,
								'origin_server_ts', timestamp,
								'unsigned', JSON(unsigned),
								'state_key', stateKey,
								'prev_content', JSON(prevContent)
							)
						),
					
						timelineId    INTEGER NOT NULL,
						timelineOrder INTEGER CHECK (timelineOrder IS NOT NULL OR stateKey IS NOT NULL),
					
						isLatestState BOOLEAN NOT NULL DEFAULT FALSE CHECK (NOT isLatestState OR stateKey NOT NULL),
					
						PRIMARY KEY (roomId, eventId),
						FOREIGN KEY (roomId, timelineId) REFERENCES room_timelines(roomId, timelineId)
							ON UPDATE CASCADE,
						FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
							ON DELETE CASCADE
					);
				""")
				stmt.execute("""
					CREATE INDEX room_timeline ON room_events (roomId, timelineId, timelineOrder DESC, type, stateKey);
				""")
				stmt.execute("""
					CREATE UNIQUE INDEX latest_room_state ON room_events (roomId, type, stateKey) WHERE isLatestState;
				""")
				stmt.execute("""
					CREATE UNIQUE INDEX compressed_state ON room_events (roomId, timelineId, type, stateKey) WHERE timelineOrder IS NULL;
				""")
				stmt.execute("""
					CREATE TRIGGER latest_room_state
						AFTER INSERT ON room_events
						WHEN NOT NEW.isLatestState AND NEW.stateKey NOT NULL
					BEGIN
						UPDATE room_events
						SET isLatestState = FALSE
						WHERE isLatestState
							AND roomId = NEW.roomId
							AND type = NEW.type
							AND stateKey = NEW.stateKey
							AND (timelineId, -COALESCE(timelineOrder, 0)) > (NEW.timelineId, -COALESCE(NEW.timelineOrder, 0));
						UPDATE room_events
						SET isLatestState = TRUE
						WHERE roomId = NEW.roomId
							AND eventId = NEW.eventId
							AND NOT EXISTS(
								SELECT 1
								FROM room_events
								WHERE isLatestState
								AND roomId = NEW.roomId
								AND type = NEW.type
								AND stateKey = NEW.stateKey
							);
					END;
				""")
				stmt.execute("""
					CREATE TABLE room_invitations
					(
						roomId   TEXT NOT NULL,
						type     TEXT NOT NULL,
						stateKey TEXT NOT NULL,
						sender   TEXT NOT NULL,
						content  TEXT NOT NULL CHECK (JSON_VALID(content)),
					
						PRIMARY KEY (roomId, type, stateKey)
					);
				""")
				stmt.execute("""
					CREATE TABLE account_data
					(
						type    TEXT NOT NULL,
						roomId  TEXT,
						content TEXT NOT NULL CHECK (JSON_VALID(content)),
					
						FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
							ON DELETE CASCADE
					);
				""")
				stmt.execute("""
					CREATE UNIQUE INDEX global_account_data ON account_data(type) WHERE roomId IS NULL;
				""")
				stmt.execute("""
					CREATE UNIQUE INDEX room_account_data ON account_data(roomId, type) WHERE roomId IS NOT NULL;
				""")
				stmt.execute("""
					CREATE TABLE room_receipts
					(
						roomId  TEXT NOT NULL,
						userId  TEXT NOT NULL,
						type    TEXT NOT NULL,
						eventId TEXT NOT NULL,
						content TEXT NOT NULL CHECK (JSON_VALID(content)),
					
						PRIMARY KEY (roomId, userId, type),
						FOREIGN KEY (roomId) REFERENCES room_metadata(roomId)
							ON DELETE CASCADE
					);					
				""")
			}
		}
	}

	suspend fun <T> read(block: (Connection) -> T): T {
		return helper.usingReadConnection { block(it) }
	}
	suspend fun <T> write(block: (Connection) -> T): T {
		return helper.usingWriteConnection { block(it) }
	}

	override suspend fun getSyncToken(): String? {
		return helper.usingReadConnection {
			it.getValue("SYNC_TOKEN")
		}
	}

	private class InsertUtils(connection: Connection): Closeable {
		private val roomSummaryStmt = connection.prepareStatement("""
			INSERT INTO room_metadata(roomId, summary, loadedMembershipTypes, unreadNotifications)
			VALUES (?, ?, ?, '{}')
			ON CONFLICT(roomId) DO UPDATE SET summary=JSON_PATCH(summary, excluded.summary);
		""")
		private val roomNotificationsStmt = connection.prepareStatement("""
			UPDATE room_metadata
			SET unreadNotifications=JSON_PATCH(unreadNotifications, ?)
			WHERE roomId = ?;
		""")
		private val eventStmt = connection.prepareStatement("""
			INSERT OR IGNORE INTO room_events(
				roomId, eventId, type, content, sender, unsigned, stateKey, prevContent, timestamp,
				timelineId, timelineOrder
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
			ON CONFLICT(roomId, eventId) DO UPDATE
				SET timelineOrder = excluded.timelineOrder
				WHERE timelineId = excluded.timelineId AND excluded.timelineOrder IS NOT NULL;
		""")
		private val accountStmt = connection.prepareStatement("""
			INSERT OR REPLACE INTO account_data(type, roomId, content) VALUES (?, ?, ?);
		""")
		private val lastOrderStmt = connection.prepareStatement("""
			SELECT MAX(timelineOrder) FROM room_events WHERE roomId = ? AND timelineId = 0;
		""")
		private val beginShiftTimelinesStmt = connection.prepareStatement("UPDATE room_timelines SET timelineId = -timelineId WHERE roomId = ?;")
		private val endShiftTimelinesStmt = connection.prepareStatement("UPDATE room_timelines SET timelineId = -timelineId + 1 WHERE roomId = ?;")
		private val newTimelineStmt = connection.prepareStatement("INSERT INTO room_timelines(roomId, timelineId, token) VALUES (?, 0, ?);")
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

		fun updateRoomNotifications(roomId: String, notificationCounts: UnreadNotificationCounts): Int {
			roomNotificationsStmt.setSerializable(1, UnreadNotificationCounts.serializer(), notificationCounts)
			roomNotificationsStmt.setString(2, roomId)
			return roomNotificationsStmt.executeUpdate()
		}

		fun insertRoomEvent(roomId: String, event: SyncEvent, timelineOrder: Long?): Int {
			eventStmt.setString(1, roomId)
			eventStmt.setString(2, event.eventId)
			eventStmt.setString(3, event.type)
			eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
			eventStmt.setString(5, event.sender)
			eventStmt.setSerializable(6, JsonElement.serializer().nullable, event.unsigned)
			eventStmt.setString(7, event.stateKey)
			eventStmt.setSerializable(8, JsonElement.serializer().nullable, event.prevContent)
			eventStmt.setLong(9, event.originServerTimestamp)
			if (timelineOrder != null) {
				eventStmt.setLong(10, timelineOrder)
			} else {
				eventStmt.setNull(10, Types.INTEGER)
			}
			return eventStmt.executeUpdate()
		}

		fun updateAccountData(roomId: String?, type: String, content: JsonElement): Int {
			accountStmt.setString(1, type)
			accountStmt.setString(2, roomId)
			accountStmt.setSerializable(3, JsonElement.serializer(), content)
			return accountStmt.executeUpdate()
		}

		fun getLastOrder(roomId: String): Long {
			lastOrderStmt.setString(1, roomId)
			val order = lastOrderStmt.executeQuery().use { rs ->
				check(rs.next())
				rs.getLong(1).takeUnless { rs.wasNull() }
			}
			// No timeline exist.
			if (order == null) {
				newTimelineStmt.setString(1, roomId)
				newTimelineStmt.setString(2, null)
				newTimelineStmt.executeUpdate()
				return 0
			}
			return order
		}

		fun createNewTimeline(roomId: String, token: String?) {
			beginShiftTimelinesStmt.setString(1, roomId)
			beginShiftTimelinesStmt.executeUpdate()

			endShiftTimelinesStmt.setString(1, roomId)
			endShiftTimelinesStmt.executeUpdate()

			newTimelineStmt.setString(1, roomId)
			newTimelineStmt.setString(2, token)
			newTimelineStmt.executeUpdate()
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
			newTimelineStmt.close()
			endShiftTimelinesStmt.close()
			beginShiftTimelinesStmt.close()
			lastOrderStmt.close()
			accountStmt.close()
			eventStmt.close()
			roomSummaryStmt.close()
		}
	}

	override suspend fun storeSync(sync: SyncResponse, token: String?) {
		helper.usingWriteConnection { conn ->
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
					for ((roomId, joinedRoom) in rooms.join) {
						val summary = joinedRoom.summary
						if (summary != null) {
							utils.updateRoomSummary(roomId, summary)
						}

						val notifications = joinedRoom.unreadNotifications
						if (notifications != null) {
							utils.updateRoomNotifications(roomId, notifications)
						}

						val timeline = joinedRoom.timeline
						if (timeline != null) {
							var order = if (timeline.limited == true) {
								// Create new batch
								utils.createNewTimeline(roomId, timeline.prevBatch!!)
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
								val changes = utils.insertRoomEvent(roomId, event, order)
								if (changes > 0) order++
							}
						}
						// else { /* insert state events ? */ }

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
					for ((roomId, room) in rooms.leave) {
						val timeline = room.timeline
						if (timeline != null) {
							var order = if (timeline.limited == true) {
								// Create new batch
								utils.createNewTimeline(roomId, timeline.prevBatch!!)
								1
							} else {
								utils.getLastOrder(roomId) + 1
							}

							val stateEvents = room.state?.events
							if (stateEvents != null) {
								for (event in stateEvents) {
									utils.insertRoomEvent(roomId, event, null)
								}
							}
							for (event in timeline.events) {
								val changes = utils.insertRoomEvent(roomId, event, order)
								if (changes > 0) order++
							}
						}
						// else { /* insert state events ? */ }

						val accountEvents = room.accountData?.events
						if (accountEvents != null) {
							for (event in accountEvents) {
								utils.updateAccountData(roomId, event.type, event.content)
							}
						}
					}

					for (roomId in (rooms.leave.keys + rooms.join.keys)) {
						utils.clearInvitedState(roomId)
					}
					for ((roomId, invitedRoom) in rooms.invite) {
						val state = invitedRoom.inviteState
						if (state != null) {
							for (event in state.events) {
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

				val oneTimeKeysCount = sync.deviceOneTimeKeysCount
				if (oneTimeKeysCount != null) {
					utils.updateOneTimeKeys(oneTimeKeysCount)
				}
			}
		}
	}

	override suspend fun getOneTimeKeysCount(): Map<String, Long> {
		return helper.usingReadConnection { conn ->
			val count = conn.getValue("ONE_TIME_KEYS_COUNT")
			if (count != null) {
				Json.decodeFromString(count)
			} else {
				emptyMap()
			}
		}
	}

	override suspend fun getJoinedRooms(userId: String): Set<String> {
		return helper.usingReadConnection { conn ->
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
		return helper.usingReadConnection { conn ->
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
						rs.getSerializable(1)
					} else {
						emptyMap()
					}
				}
			}
		}
	}

	override suspend fun getAccountData(type: String): JsonObject? {
		return helper.usingReadConnection { conn ->
			val query = "SELECT content FROM account_data WHERE roomId IS NULL AND type = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, type)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable<JsonObject>(1)
					} else {
						null
					}
				}
			}
		}
	}


	override suspend fun getState(roomId: String, type: String, stateKey: String): JsonObject? {
		return helper.usingReadConnection { conn ->
			val query = "SELECT content FROM room_events WHERE roomId = ? AND type = ? AND stateKey = ? AND isLatestState;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, type)
				stmt.setString(3, stateKey)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1)
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun getState(roomId: String, type: String): Map<String, JsonObject> {
		return read { conn ->
			val sql = "SELECT stateKey, content FROM room_events WHERE roomId = ? AND type = ? AND isLatestState;"
			conn.prepareStatement(sql).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, type)
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildMap {
						while (rs.next()) {
							val stateKey = rs.getString(1)
							val content = rs.getSerializable(2, JsonObject.serializer())
							put(stateKey, content)
						}
					}
				}
			}
		}
	}

	override suspend fun getAccountData(roomId: String, type: String): JsonObject? {
		return helper.usingReadConnection { conn ->
			val query = "SELECT content FROM account_data WHERE roomId = ? AND type = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, type)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable<JsonObject>(1)
					} else {
						null
					}
				}
			}
		}
	}

	override suspend fun getMembers(roomId: String, membership: Membership): Set<String> {
		return helper.usingReadConnection { conn ->
			val query = """
					SELECT stateKey
					FROM room_events
					WHERE roomId = ? AND type = 'm.room.member' AND JSON_EXTRACT(content, '${'$'}.membership') = ?
					   AND isLatestState
				"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, membership.name.lowercase())
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

	override suspend fun getReadReceipts(roomId: String, eventId: String): Map<String, ReceiptContent.Receipt> {
		return helper.usingReadConnection { conn ->
			val query = """
				SELECT userId, content
				FROM room_receipts
				WHERE roomId = ? AND eventId = ? AND type = ?;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, eventId)
				stmt.setString(3, "m.read")
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildMap {
						while (rs.next()) {
							put(
								rs.getString(1),
								rs.getSerializable(2)
							)
						}
					}
				}
			}
		}
	}

	override suspend fun getSummary(roomId: String): RoomSummary {
		return helper.usingReadConnection { conn ->
			val query = "SELECT summary FROM room_metadata WHERE roomId = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1)
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}
		}
	}

	override suspend fun getUnreadNotificationCounts(roomId: String): UnreadNotificationCounts {
		return helper.usingReadConnection { conn ->
			val query = "SELECT unreadNotifications FROM room_metadata WHERE roomId = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getSerializable(1)
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}
		}
	}

	override suspend fun getLazyLoadingState(roomId: String): SyncStore.LazyLoadingState {
		return helper.usingReadConnection { conn ->
			val query = """
				WITH
					room_tokens(roomId, token, idx) AS (
						SELECT roomId, token, ROW_NUMBER() OVER (PARTITION BY roomId ORDER BY timelineId DESC)
						FROM room_timelines
					)
				SELECT token, loadedMembershipTypes
				FROM room_metadata
				LEFT JOIN room_tokens USING (roomId) 
				WHERE roomId = ? AND idx = 1;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						SyncStore.LazyLoadingState(
							rs.getString(1),
							rs.getSerializable(2)
						)
					} else {
						throw NoSuchElementException("No room with id '$roomId'")
					}
				}
			}
		}
	}

	private fun Connection.getNewState(roomId: String, eventIds: Set<String>): List<SyncEvent> {
		val queryNewState = """
				WITH new_events(eventId) AS (SELECT value FROM JSON_EACH(?2))
				SELECT JSON_GROUP_ARRAY(json)
				FROM room_events
				JOIN new_events USING(eventId)
				WHERE roomId = ?1 AND isLatestState
			"""
		return prepareStatement(queryNewState).use { stmt ->
			stmt.setString(1, roomId)
			stmt.setSerializable(2, SetSerializer(String.serializer()), eventIds)
			stmt.executeQuery().use { rs ->
				check(rs.next())
				rs.getSerializable(1)
			}
		}
	}

	override suspend fun storeMembers(roomId: String, response: GetMembersByRoom.Response): List<SyncEvent> {
		return helper.usingWriteConnection { conn ->
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

				for (event in response.chunk.orEmpty()) {
					require(event.roomId == roomId)
					require(event.type == "m.room.member")

					stmt.setString(2, event.eventId)
					stmt.setString(3, event.type)
					stmt.setSerializable(4, MemberContent.serializer(), event.content)
					stmt.setString(5, event.sender)
					stmt.setLong(6, event.originServerTimestamp)
					stmt.setSerializable(7, JsonObject.serializer().nullable, event.unsigned)
					stmt.setString(8, event.stateKey)
					stmt.setSerializable(9, MemberContent.serializer().nullable, event.prevContent)
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
				val memberships = response.chunk.orEmpty().asSequence()
					.map { it.content.membership }.toSet()

				stmt.setString(1, roomId)
				stmt.setSerializable(2, SetSerializer(Membership.serializer()), memberships)
				stmt.executeUpdate()
			}

			conn.getNewState(roomId, insertedEventIds)
		}
	}


	override suspend fun getPaginationToken(roomId: String, eventId: String): String? {
		return helper.usingReadConnection { conn ->
			// Find first event in timeline and get corresponding token.
			val query = """
				SELECT token
				FROM room_timelines
				JOIN room_events USING(roomId, timelineId)
				WHERE roomId = ? AND eventId = ?;
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

	override suspend fun storeTimelineEvents(roomId: String, response: GetRoomEvents.Response): List<SyncEvent> {
		val timelineEvents = response.chunk
		if (timelineEvents.isNullOrEmpty()) {
			// check(response.state.isNullOrEmpty())
			return emptyList()
		}

		return helper.usingWriteConnection { conn ->
			val getTimelineIdQuery = "SELECT timelineId FROM room_timelines WHERE roomId = ? AND token = ?"
			val timelineId = conn.prepareStatement(getTimelineIdQuery).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, response.start)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getInt(1)
					} else {
						throw NoSuchElementException(
							"Pagination token '${response.start}' not in Room($roomId) timelines.")
					}
				}
			}

			conn.prepareStatement("UPDATE room_timelines SET token = NULL WHERE roomId = ? AND timelineId = ?;").use {
				it.setString(1, roomId)
				it.setInt(2, timelineId)
				val changes = it.executeUpdate()
				check(changes == 1)
			}

			val shiftTimelineStmt = conn.prepareStatement("UPDATE room_events SET timelineOrder = timelineOrder + ? WHERE roomId = ? AND timelineId = ?;")
			shiftTimelineStmt.setString(2, roomId)
			shiftTimelineStmt.setInt(3, timelineId)

			val eventStmt = conn.prepareStatement("""
				INSERT INTO room_events(
					roomId, eventId, type, content, sender, stateKey, prevContent, timestamp, unsigned,
					timelineId, timelineOrder
				)
				VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
				ON CONFLICT(roomId, eventId) DO UPDATE
					SET timelineOrder = ?11
					WHERE timelineOrder IS NULL AND timelineId = ?10;
			""")

			val insertedEventIds = mutableSetOf<String>()
			try {
				val duplicateState = response.state.map { it.eventId }.toSet()
				val events = timelineEvents.dropLastWhile { it.eventId in duplicateState }

				shiftTimelineStmt.setInt(1, events.size)
				shiftTimelineStmt.executeUpdate()

				var timelineOrder = events.size
				var postInsertShift = 0

				for (event in events) {
					check(roomId == event.roomId)
					eventStmt.setString(1, event.roomId)
					eventStmt.setString(2, event.eventId)
					eventStmt.setString(3, event.type)
					eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
					eventStmt.setString(5, event.sender)
					eventStmt.setString(6, event.stateKey)
					eventStmt.setSerializable(7, JsonElement.serializer().nullable, event.prevContent)
					eventStmt.setLong(8, event.originServerTimestamp)
					eventStmt.setSerializable(9, JsonElement.serializer().nullable, event.unsigned)
					eventStmt.setInt(10, timelineId)
					eventStmt.setInt(11, timelineOrder)
					val changes = eventStmt.executeUpdate()
					// If no rows were inserted/updated, then there was a conflict between two timeline events.
					if (changes == 0) {
						val (id, _) = conn.getTimelineIdAndOrder(roomId, event.eventId)
						// If server returned overlapping event ... we skip it.
						if (id == timelineId) {
							postInsertShift++
							continue
						}

						// If event was from next oldest timeline,
						// it means we have to stitch two timeline chunks together.
						if (id == timelineId + 1) {
							// By just breaking here we assume we already have all the
							// skipped events stored in earlier timeline.
							// TODO: This is not necessarily true, needs to be optimised.
							break
						}

						// Duplicate event based on DAG ordering vs stream ordering.
						postInsertShift++
						continue
					} else {
						insertedEventIds.add(event.eventId)
					}
					timelineOrder--
				}

				if (postInsertShift > 0) {
					// Deallocate the space for the duplicate events
					shiftTimelineStmt.setInt(1, -postInsertShift)
					shiftTimelineStmt.executeUpdate()
					timelineOrder -= postInsertShift
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

					// Merge current timeline into older timeline
					val sql = """
						UPDATE room_events
						SET timelineId = timelineId + 1, timelineOrder = timelineOrder + ?
						WHERE roomId = ? AND timelineId = ?
					"""
					conn.prepareStatement(sql).use { stmt ->
						// Shift all our events forward to accommodate older timeline.
						stmt.setInt(1, maxOrderOfPreviousTimeline - timelineOrder)
						stmt.setString(2, roomId)
						stmt.setInt(3, timelineId)
						stmt.executeUpdate()
					}
					conn.prepareStatement("DELETE FROM room_timelines WHERE roomId = ? AND timelineId = ?;").use { stmt ->
						stmt.setString(1, roomId)
						stmt.setInt(2, timelineId)
						val changes = stmt.executeUpdate()
						check(changes == 1) // Only this row should be deleted. No foreign key cascades should happen.
					}

					// Maintain contiguous timelineIds.
					conn.prepareStatement("UPDATE room_timelines SET timelineId = -timelineId WHERE roomId = ? AND timelineId > ?;").use { stmt ->
						stmt.setString(1, roomId)
						stmt.setInt(2, timelineId)
						stmt.executeUpdate()
					}
					conn.prepareStatement("UPDATE room_timelines SET timelineId = -timelineId - 1 WHERE roomId = ? AND timelineId < 0;").use { stmt ->
						stmt.setString(1, roomId)
						stmt.executeUpdate()
					}
				} else {
					for (event in response.state) {
						eventStmt.setString(1, roomId)
						eventStmt.setString(2, event.eventId)
						eventStmt.setString(3, event.type)
						eventStmt.setSerializable(4, JsonElement.serializer(), event.content)
						eventStmt.setString(5, event.sender)
						eventStmt.setString(6, event.stateKey)
						eventStmt.setSerializable(7, JsonElement.serializer().nullable, event.prevContent)
						eventStmt.setLong(8, event.originServerTimestamp)
						eventStmt.setSerializable(9, JsonElement.serializer().nullable, event.unsigned)
						eventStmt.setInt(10, timelineId)
						eventStmt.setNull(11, Types.INTEGER)
						val changes = eventStmt.executeUpdate()
						if (changes > 0) {
							insertedEventIds.add(event.eventId)
						}
					}
					if (response.end != null) {
						val sql = "UPDATE room_timelines SET token = ? WHERE roomId = ? AND timelineId = ?;"
						conn.prepareStatement(sql).use { stmt ->
							stmt.setString(1, response.end)
							stmt.setString(2, roomId)
							stmt.setInt(3, timelineId)
							stmt.executeUpdate()
						}
					}
				}
			} finally {
				eventStmt.close()
				shiftTimelineStmt.close()
			}

			conn.getNewState(roomId, insertedEventIds)
		}
	}

	suspend fun clear() {
		helper.usingWriteConnection { conn ->
			conn.usingStatement { stmt ->
				stmt.execute("DELETE FROM key_value_store;")
				stmt.execute("DELETE FROM room_metadata;")
				stmt.execute("DELETE FROM account_data;")
			}
		}
	}
}
