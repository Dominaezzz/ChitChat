package me.dominaezzz.chitchat.sdk.core.internal

import io.github.matrixkt.api.GetMembersByRoom
import io.github.matrixkt.api.GetRoomEvents
import io.github.matrixkt.api.GetRoomStateWithKey
import io.github.matrixkt.models.Direction
import io.github.matrixkt.models.MatrixError
import io.github.matrixkt.models.MatrixException
import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.SyncEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.TypingContent
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.models.sync.Event
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.models.RoomTimeline
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.SyncStore

class RoomImpl(
	override val id: String,
	private val scope: CoroutineScope,
	private val syncFlow: Flow<SyncResponse>,
	private val client: HttpClient,
	private val loginSession: LoginSession,
	private val store: SyncStore,
	private val shareConfig: SharingStarted
) : Room {
	override val ownUserId: String
		get() = loginSession.userId

	override val timelineEvents: Flow<MatrixEvent> = syncFlow.mapNotNull { it.rooms }
		.transform { rooms ->
			emitList(rooms.join[id]?.timeline?.events)
			emitList(rooms.leave[id]?.timeline?.events)
		}

	private val stateSemaphore = Semaphore(1)
	private val lazyStateFlow = MutableSharedFlow<SyncEvent>()
	private val syncStateFlow = syncFlow.mapNotNull { it.rooms }
		.onEach { stateSemaphore.withPermit { /* Wait for lazy state event emissions */ } }
		.transform { rooms ->
			emitList(rooms.join[id]?.state?.events)
			emitList(rooms.join[id]?.timeline?.events?.filter { it.stateKey != null })

			emitList(rooms.leave[id]?.state?.events)
			emitList(rooms.leave[id]?.timeline?.events?.filter { it.stateKey != null })
		}
	@OptIn(ExperimentalCoroutinesApi::class)
	private val stateFlow = merge(lazyStateFlow, syncStateFlow)

	private val joinedFlow = syncFlow.mapNotNull { it.rooms?.join?.get(id) }

	private val stateFlowMap = MapOfFlows<Pair<String, String>, JsonObject?> { (type, stateKey) ->
		stateFlow.filter { it.type == type && it.stateKey == stateKey }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = store.getState(id, type, stateKey)
				emit(content)
			}
			.shareIn(scope, shareConfig, 1)
	}

	private val accountDataFlowMap = MapOfFlows<String, JsonObject?> { type ->
		syncFlow.mapNotNull { it.rooms }
			.transform { rooms ->
				emitList(rooms.join[id]?.accountData?.events)
				emitList(rooms.leave[id]?.accountData?.events)
			}
			.filter { it.type == type }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = store.getAccountData(id, type)
				emit(content)
			}
			.shareIn(scope, shareConfig, 1)
	}


	private fun getMembers(membership: Membership): SharedFlow<Set<String>> {
		return flow {
			val lazyState = store.getLazyLoadingState(id)
			if (membership !in lazyState.loaded) {
				if (lazyState.token != null) {
					val response = client.rpc(
						GetMembersByRoom(GetMembersByRoom.Url(id, lazyState.token, membership)),
						loginSession.accessToken
					)
					// Pause sync state updates, so we can emit (potentially) older events first.
					stateSemaphore.withPermit {
						val newState = store.storeMembers(id, response)
						newState.forEach { lazyStateFlow.emit(it) }
					}
				}
			}

			var members = store.getMembers(id, membership)
			emit(members)

			stateFlow.filter { it.type == "m.room.member" }
				.map { it.stateKey!! to MatrixJson.decodeFromJsonElement(MemberContent.serializer(), it.content) }
				.collect { (userId, content) ->
					if (userId in members) {
						if (content.membership != membership) {
							members -= userId
							emit(members)
						}
					} else {
						if (content.membership == membership) {
							members += userId
							emit(members)
						}
					}
				}
		}.shareIn(scope, shareConfig, 1)
	}

	override val joinedMembers: Flow<Set<String>> = getMembers(Membership.JOIN)

	override val invitedMembers: Flow<Set<String>> = getMembers(Membership.INVITE)

	override val heroes: Flow<Set<String>?> = flow {
		val summary = store.getSummary(id)
		val heroes = summary.heroes
		emit(heroes?.toSet())
		emitAll(syncFlow.mapNotNull { it.rooms }
			.mapNotNull { it.join[id]?.summary?.heroes }
			.map { it.toSet() })
	}.shareIn(scope, shareConfig, 1)

	override val joinedMemberCount: Flow<Int> = flow {
		val summary = store.getSummary(id)
		val count = summary.joinedMemberCount
		if (count != null) {
			emit(count.toInt())
			emitAll(joinedFlow.mapNotNull { it.summary?.joinedMemberCount?.toInt() })
		} else {
			emitAll(joinedMembers.map { it.size })
		}
	}.shareIn(scope, shareConfig, 1)

	override val invitedMemberCount: Flow<Int> = flow {
		val summary = store.getSummary(id)
		val count = summary.invitedMemberCount
		if (count != null) {
			emit(count.toInt())
			emitAll(joinedFlow.mapNotNull { it.summary?.invitedMemberCount?.toInt() })
		} else {
			emitAll(invitedMembers.map { it.size })
		}
	}.shareIn(scope, shareConfig, 1)

	override val notificationCount: Flow<Int> = flow {
		val notificationCounts = store.getUnreadNotificationCounts(id)
		emit(notificationCounts.notificationCount?.toInt() ?: 0)
		emitAll(joinedFlow.mapNotNull { it.unreadNotifications?.notificationCount?.toInt() })
	}.shareIn(scope, shareConfig, 1)

	override val highlightCount: Flow<Int> = flow {
		val notificationCounts = store.getUnreadNotificationCounts(id)
		emit(notificationCounts.highlightCount?.toInt() ?: 0)
		emitAll(joinedFlow.mapNotNull { it.unreadNotifications?.highlightCount?.toInt() })
	}.shareIn(scope, shareConfig, 1)

	override fun <T> getState(type: String, stateKey: String, serializer: KSerializer<T>): Flow<T?> {
		return stateFlowMap.getFlow(type to stateKey).decodeJson(serializer)
	}

	override fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?> {
		return accountDataFlowMap.getFlow(type).decodeJson(serializer)
	}

	private val lazyMemberMap = MapOfFlows<String, MemberContent?> { userId ->
		flow {
			val content = try {
				client.rpc(
					GetRoomStateWithKey(GetRoomStateWithKey.Url(id, "m.room.member", userId)),
					loginSession.accessToken
				)
			} catch (e: MatrixException) {
				if (e.error is MatrixError.NotFound) {
					null
				} else {
					throw e
				}
			}
			val member = content?.let { MatrixJson.decodeFromJsonElement(MemberContent.serializer(), it) }
			emit(member)
		}.shareIn(scope, shareConfig, 1)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun getMember(userId: String): Flow<MemberContent?> {
		val localState = getState("m.room.member", userId, MemberContent.serializer())
		val lazyFallback = flow {
			val state = store.getLazyLoadingState(id)
			if (state.loaded == Membership.values().toSet()) {
				emit(null)
			} else {
				emitAll(lazyMemberMap.getFlow(userId))
			}
		}
		return localState.coalesce(lazyFallback)
	}

	private val ephemeralEvents: Flow<Event> = joinedFlow.mapNotNull { it.ephemeral }
		.transform { for (event in it.events) emit(event) }

	override val typingUsers: Flow<List<String>> = ephemeralEvents.filter { it.type == "m.typing" }
		.map { MatrixJson.decodeFromJsonElement(TypingContent.serializer(), it.content) }
		.map { it.userIds }
		.onStart { emit(emptyList()) }
		.shareIn(scope, shareConfig, 1)

	override val readReceipts: Flow<Map<String, List<Pair<String, ReceiptContent.Receipt>>>> = ephemeralEvents
		.filter { it.type == "m.receipt" }
		.map {}
		.onStart { emit(Unit) }
		.map { store.getReadReceipts(id).groupBy({ it.eventId }, { it.userId to it.receipt }) }
		.shareIn(scope, shareConfig, 1)

	override suspend fun backPaginate(eventId: String, limit: Int): Boolean {
		val token = store.getPaginationToken(id, eventId)
		if (token == null) {
			println("No token for back pagination")
			// Cannot paginate backwards from this event
			return false
		}

		val response = client.rpc(
			GetRoomEvents(GetRoomEvents.Url(id, token, null, Direction.BACKWARD, limit.toLong())),
			loginSession.accessToken
		)

		stateSemaphore.withPermit {
			val newState = store.storeTimelineEvents(id, response)
			newState.forEach { lazyStateFlow.emit(it) }
		}
		return true
	}

	override fun createTimelineView(): RoomTimeline {
		return RoomTimeline(this, store)
	}

	// ------------------- Utilities -----------------------

	private suspend fun <T> FlowCollector<T>.emitList(items: List<T>?) {
		if (items != null) {
			for (item in items) {
				emit(item)
			}
		}
	}
}
