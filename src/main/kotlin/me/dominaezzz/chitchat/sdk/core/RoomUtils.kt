package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.events.contents.TagContent
import io.github.matrixkt.models.events.contents.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

val Room.create: Flow<CreateContent>
	get() = getState("m.room.create", "", CreateContent.serializer())
		.map { checkNotNull(it) { "Rooms must have a `m.room.create` event." } }

val Room.name: Flow<NameContent?>
	get() = getState("m.room.name", "", NameContent.serializer())

val Room.canonicalAlias: Flow<CanonicalAliasContent?>
	get() = getState("m.room.canonical_alias", "", CanonicalAliasContent.serializer())

val Room.topic: Flow<TopicContent?>
	get() = getState("m.room.topic", "", TopicContent.serializer())

val Room.avatar: Flow<AvatarContent?>
	get() = getState("m.room.avatar", "", AvatarContent.serializer())

val Room.guestAccess: Flow<GuestAccessContent?>
	get() = getState("m.room.guest_access", "", GuestAccessContent.serializer())

val Room.historyVisibility: Flow<HistoryVisibilityContent?>
	get() = getState("m.room.history_visibility", "", HistoryVisibilityContent.serializer())

val Room.joinRules: Flow<JoinRulesContent?>
	get() = getState("m.room.join_rules", "", JoinRulesContent.serializer())

val Room.pinnedEvents: Flow<PinnedEventsContent?>
	get() = getState("m.room.pinned_events", "", PinnedEventsContent.serializer())

val Room.encryption: Flow<EncryptionContent?>
	get() = getState("m.room.encryption", "", EncryptionContent.serializer())

val Room.powerLevels: Flow<PowerLevelsContent?>
	get() = getState("m.room.power_levels", "", PowerLevelsContent.serializer())

val Room.serverAcl: Flow<ServerAclContent?>
	get() = getState("m.room.server_acl", "", ServerAclContent.serializer())

val Room.tombstone: Flow<TombstoneContent?>
	get() = getState("m.room.tombstone", "", TombstoneContent.serializer())


val Room.tags: Flow<Map<String, TagContent.Tag>>
	get() = getAccountData("m.tag", TagContent.serializer())
		.map { it?.tags ?: emptyMap() }

@OptIn(ExperimentalCoroutinesApi::class)
fun Room.getDisplayName(currentUserId: String): Flow<String> {
	fun String.nullIfEmpty() = takeIf { it.isNotEmpty() }
	fun <E, T : Collection<E>> T.nullIfEmpty() = takeIf { it.isNotEmpty() }
	fun nameFromMembers(userIds: List<String>): Flow<String> {
		val nameFlows = userIds.map { userId ->
			getState("m.room.member", userId, MemberContent.serializer())
				.map { it?.displayName ?: userId }
		}
		return combine(nameFlows) { it.joinToString() }
	}

	val roomName = name.map { it?.name?.nullIfEmpty() }
	val roomAlias = canonicalAlias.map { it?.alias?.nullIfEmpty() }
	val roomMembers = joinedMembers.map { (it - currentUserId).sorted().take(5).nullIfEmpty() }
		.coalesce(invitedMembers.map { (it - currentUserId).sorted().take(5).nullIfEmpty() })
		.flatMapLatest { heroes -> heroes?.let(::nameFromMembers) ?: flowOf(null) }

	return roomName.coalesce(roomAlias).coalesce(roomMembers).coalesce(flowOf("Empty Room"))
}

@OptIn(ExperimentalCoroutinesApi::class)
fun Room.getDisplayAvatar(currentUserId: String): Flow<String?> {
	return avatar.map { it?.url }
		.coalesce(
			joinedMembers.map { (it - currentUserId).minOrNull() }
				.coalesce(invitedMembers.map { (it - currentUserId).minOrNull() })
				.flatMapLatest { userId ->
					if (userId != null) {
						getState("m.room.member", userId, MemberContent.serializer())
							.map { it?.avatarUrl }
					} else {
						flowOf(null)
					}
				})
}
