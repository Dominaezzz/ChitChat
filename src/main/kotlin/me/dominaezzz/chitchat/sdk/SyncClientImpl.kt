package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.LoginSession
import me.dominaezzz.chitchat.db.getSerializable
import me.dominaezzz.chitchat.db.usingConnection
import me.dominaezzz.chitchat.db.usingStatement

class SyncClientImpl(
	private val syncFlow: Flow<SyncResponse>,
	private val scope: CoroutineScope,
	private val loginSession: LoginSession,
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore
) : SyncClient {
	private val shareConfig = SharingStarted.WhileSubscribed(1000)

	override val joinedRooms: Flow<Map<String, Room>> = flow {
		var rooms = getJoinedRoomsFromDb().associateWith { createRoom(it) }
		emit(rooms)

		syncFlow.mapNotNull { it.rooms }.collect { updates ->
			var wasUpdated = false
			for (roomId in updates.join.keys) {
				if (roomId !in rooms) {
					rooms += roomId to createRoom(roomId)
					wasUpdated = true
				}
			}
			for (roomId in updates.leave.keys) {
				if (roomId in rooms) {
					rooms -= roomId
					wasUpdated = true
				}
			}
			if (wasUpdated) {
				emit(rooms)
			}
		}
	}.shareIn(scope, shareConfig, 1)

	private fun createRoom(roomId: String): Room {
		return RoomImpl(roomId, scope, syncFlow, client, dbSemaphore)
	}


	private val accountDataFlowMap = MapOfFlows<String, JsonObject?> { type ->
		syncFlow.mapNotNull { it.accountData }
			.transform { data -> data.events.forEach { emit(it) } }
			.filter { it.type == type }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = getAccountDataFromDb(type)
				emit(content)
			}
			.shareIn(scope, shareConfig, 1)
	}

	override fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?> {
		return accountDataFlowMap.getFlow(type).decodeJson(serializer)
	}

	// ------------------- Database accessors (to be injected) -----------------------

	private suspend fun getRoomIdsFromDb(): Set<String> {
		return withContext(Dispatchers.IO) {
			usingStatement { stmt ->
				val query = """SELECT roomId FROM room_metadata;"""
				stmt.executeQuery(query).use { rs ->
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

	private suspend fun getJoinedRoomsFromDb(): Set<String> {
		return withContext(Dispatchers.IO) {
			val query = """
				SELECT room_metadata.roomId
				FROM room_metadata
				JOIN room_events state
				  ON room_metadata.roomId = state.roomId AND isLatestState
				   AND type = 'm.room.member' AND stateKey = ?
				   AND JSON_EXTRACT(content, '${'$'}.membership') = 'join';
			"""
			usingConnection { conn ->
				conn.prepareStatement(query).use { stmt ->
					stmt.setString(1, loginSession.userId)
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
	}

	private suspend fun getAccountDataFromDb(type: String): JsonObject? {
		return withContext(Dispatchers.IO) {
			usingConnection { conn ->
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
	}
}
