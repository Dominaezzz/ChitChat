package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.TagContent
import io.github.matrixkt.models.events.contents.TypingContent
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.models.sync.Event
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.models.RoomTimeline

class RoomImpl(
	override val id: String,
	private val scope: CoroutineScope,
	private val syncFlow: Flow<SyncResponse>,
	private val client: MatrixClient,
	private val store: SyncStore
) : Room {
	private val shareConfig = SharingStarted.WhileSubscribed(1000)

	private val stateFlow = syncFlow.mapNotNull { it.rooms }
		.transform { rooms ->
			emitList(rooms.join[id]?.state?.events)
			emitList(rooms.join[id]?.timeline?.events?.filter { it.stateKey != null })

			emitList(rooms.leave[id]?.state?.events)
			emitList(rooms.leave[id]?.timeline?.events?.filter { it.stateKey != null })
		}

	private val stateFlowMap = MapOfFlows<Pair<String, String>, JsonObject?> { (type, stateKey) ->
		stateFlow.filter { it.type == type && it.stateKey == stateKey }
			.map { it.content.takeIf { true } }
			.onStart {
				val content = store.getState(id, type, stateKey)
				emit(content)
			}
			.shareIn(scope, shareConfig, 1)
	}

	private fun getState(type: String, stateKey: String): Flow<JsonObject?> {
		return stateFlowMap.getFlow(type to stateKey)
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

	private fun getAccountData(type: String): Flow<JsonObject?> {
		return accountDataFlowMap.getFlow(type)
	}


	private fun getMembers(membership: Membership): SharedFlow<Set<String>> {
		return flow {
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

	override fun <T> getState(type: String, stateKey: String, serializer: KSerializer<T>): Flow<T?> {
		return getState(type, stateKey).decodeJson(serializer)
	}

	override fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?> {
		return getAccountData(type).decodeJson(serializer)
	}

	override val create: Flow<CreateContent> get() = TODO("Not yet implemented")

	override val name: Flow<NameContent?> = getState("m.room.name", "", NameContent.serializer())
	override val canonicalAlias: Flow<CanonicalAliasContent?> = getState("m.room.canonical_alias", "", CanonicalAliasContent.serializer())
	override val topic: Flow<TopicContent?> = getState("m.room.topic", "", TopicContent.serializer())
	override val avatar: Flow<AvatarContent?> = getState("m.room.avatar", "", AvatarContent.serializer())
	override val guestAccess: Flow<GuestAccessContent?> = getState("m.room.guest_access", "", GuestAccessContent.serializer())
	override val historyVisibility: Flow<HistoryVisibilityContent?> = getState("m.room.history_visibility", "", HistoryVisibilityContent.serializer())
	override val joinRules: Flow<JoinRulesContent?> = getState("m.room.join_rules", "", JoinRulesContent.serializer())
	override val pinnedEvents: Flow<PinnedEventsContent?> = getState("m.room.pinned_events", "", PinnedEventsContent.serializer())
	override val encryption: Flow<EncryptionContent?> = getState("m.room.encryption", "", EncryptionContent.serializer())
	override val powerLevels: Flow<PowerLevelsContent?> = getState("m.room.power_levels", "", PowerLevelsContent.serializer())
	override val serverAcl: Flow<ServerAclContent?> = getState("m.room.server_acl", "", ServerAclContent.serializer())
	override val tombstone: Flow<TombstoneContent?> = getState("m.room.tombstone", "", TombstoneContent.serializer())

	private val joinedFlow = syncFlow.mapNotNull { it.rooms?.join?.get(id) }
	private val ephemeralEvents: Flow<Event> = joinedFlow.mapNotNull { it.ephemeral }
		.transform { for (event in it.events) emit(event) }

	override val typingUsers: Flow<List<String>> = ephemeralEvents.filter { it.type == "m.typing" }
		.map { MatrixJson.decodeFromJsonElement(TypingContent.serializer(), it.content) }
		.map { it.userIds }
		.shareIn(scope, shareConfig, 1)

	override val tags: Flow<Map<String, TagContent.Tag>> = getAccountData("m.tag", TagContent.serializer())
		.map { it?.tags ?: emptyMap() }

	override val readReceipts: Flow<Map<String, List<Pair<String, ReceiptContent.Receipt>>>> = ephemeralEvents
		.filter { it.type == "m.receipt" }
		.map {}
		.onStart { emit(Unit) }
		.map { store.getReadReceipts(id).groupBy({ it.eventId }, { it.userId to it.receipt }) }
		.shareIn(scope, shareConfig, 1)

	override fun createTimelineView(): RoomTimeline {
		val timeline = RoomTimeline(id, syncFlow, client, store)
		scope.launch { timeline.run() }
		return timeline
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
