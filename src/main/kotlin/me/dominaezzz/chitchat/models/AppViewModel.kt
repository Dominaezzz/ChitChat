package me.dominaezzz.chitchat.models

import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
import me.dominaezzz.chitchat.db.*

class AppViewModel(
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore
) {
	private val _syncFlow = MutableSharedFlow<SyncResponse>()

	suspend fun sync() {
		val sync = sync(client, dbSemaphore)
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
