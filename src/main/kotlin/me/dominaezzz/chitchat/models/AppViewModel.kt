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
		return syncClient.joinedRooms
			.flatMapLatest { joinedRooms ->
				val roomHeaders = joinedRooms.values.map { room ->
					combine(
						room.getDisplayName(session.userId),
						room.getDisplayAvatar(session.userId),
						room.joinedMembers.map { it.size }
					) { displayName, displayAvatar, memberCount ->
						RoomHeader(
							room.id,
							displayName,
							memberCount,
							displayAvatar
						)
					}
				}
				combine(roomHeaders) { headers -> headers.sortedBy { it.displayName } }
			}
	}
}
