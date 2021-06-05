package me.dominaezzz.chitchat.sdk.core.internal

import io.github.matrixkt.api.Sync
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.events.StrippedState
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.DeserializationStrategy
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.SyncClient
import me.dominaezzz.chitchat.sdk.core.SyncStore

class SyncClientImpl(
	private val scope: CoroutineScope,
	private val loginSession: LoginSession,
	private val client: HttpClient,
	private val store: SyncStore
) : SyncClient {
	private val shareConfig = SharingStarted.WhileSubscribed(1000, 0)

	private val _syncFlow = MutableSharedFlow<SyncResponse>()
	override val syncFlow: SharedFlow<SyncResponse> = _syncFlow

	override suspend fun sync(timeout: Long?, setPresence: Presence?) {
		val syncToken = store.getSyncToken()

		println("Syncing with '$syncToken' as token")
		val sync = client.rpc(
			Sync(Sync.Url(since = syncToken, setPresence = setPresence, timeout = timeout)),
			loginSession.accessToken
		)

		println("Saving sync response")
		store.storeSync(sync, syncToken)
		println("Saved sync response")

		// Should cancellation be disabled here?
		// We don't really want to miss a sync if it's been persisted.
		_syncFlow.emit(sync)
	}

	override val oneTimeKeysCount: Flow<Map<String, Long>> = flow {
		var counts = store.getOneTimeKeysCount()
		emit(counts)

		syncFlow.mapNotNull { it.deviceOneTimeKeysCount }
			.distinctUntilChanged() // Synapse likes to repeat itself.
			.filter { it.isNotEmpty() }
			.collect { newCounts ->
				counts += newCounts
				emit(counts)
			}
	}.shareIn(scope, shareConfig, 1)

	override val joinedRooms: Flow<Map<String, Room>> = flow {
		var rooms = store.getJoinedRooms(loginSession.userId).associateWith { createRoom(it) }
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

	override val invitedRooms: Flow<Map<String, List<StrippedState>>> = flow {
		var invites = store.getInvitations()
		emit(invites)

		syncFlow.mapNotNull { it.rooms }
			.transform { updates ->
				val hasNewInvites = updates.invite.keys.any { it !in invites.keys }
				val invitesWereDeleted = updates.leave.keys.any { it in invites.keys }
				if (hasNewInvites || invitesWereDeleted) {
					emit(Unit)
				}
			}
			.collect {
				invites = store.getInvitations()
				emit(invites)
			}
	}.shareIn(scope, shareConfig, 1)

	private fun createRoom(roomId: String): Room {
		return JoinedRoomImpl(roomId, scope, syncFlow, client, loginSession, store, shareConfig)
	}


	private val accountDataFlowMap = MapOfFlows<Pair<String, DeserializationStrategy<*>>, Any?> { (type, deserializer) ->
		syncFlow.mapNotNull { it.accountData }
			.transform { data -> data.events.forEach { emit(it) } }
			.filter { it.type == type }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = store.getAccountData(type)
				emit(content)
			}
			.decodeJson(deserializer)
			.shareIn(scope, shareConfig, 1)
	}

	override fun <T> getAccountData(type: String, deserializer: DeserializationStrategy<T>): Flow<T?> {
		@Suppress("UNCHECKED_CAST")
		return accountDataFlowMap.getFlow(type to deserializer) as Flow<T?>
	}
}
