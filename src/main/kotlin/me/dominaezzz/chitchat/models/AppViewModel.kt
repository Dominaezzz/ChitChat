package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import me.dominaezzz.chitchat.LoginSession
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.*

class AppViewModel(
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore,
	private val session: LoginSession
) {
	private val _syncFlow = MutableSharedFlow<SyncResponse>()
	val syncClient: SyncClient = SyncClientImpl(
		_syncFlow, CoroutineScope(SupervisorJob()), session, client, dbSemaphore)

	suspend fun sync() {
		val sync = sync(client, dbSemaphore)
		_syncFlow.emit(sync)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	fun getRooms(): Flow<List<RoomHeader>> {
		val comparator = compareByDescending<RoomHeader> { it.favourite != null }
			.then(nullsLast(compareBy { it.favourite?.order }))
			.thenBy { it.lowPriority != null }
			.then(nullsLast(compareBy { it.lowPriority?.order }))
			.thenBy { it.displayName }

		return syncClient.joinedRooms
			.flatMapLatest { joinedRooms ->
				val roomHeaders = joinedRooms.values.map { room ->
					combine(
						room.getDisplayName(session.userId),
						room.getDisplayAvatar(session.userId),
						room.joinedMembers.map { it.size },
						room.tags
					) { displayName, displayAvatar, memberCount, tags ->
						RoomHeader(
							room.id,
							room,
							displayName,
							memberCount,
							displayAvatar,
							tags["m.favourite"],
							tags["m.lowpriority"]
						)
					}
				}
				combine(roomHeaders) { headers -> headers.sortedWith(comparator) }
			}
	}
}
