package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.events.contents.TagContent
import io.github.matrixkt.events.contents.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.sdk.core.internal.coalesce

val Room.create: Flow<CreateContent>
	get() = getState<CreateContent>("m.room.create", "")
		.map { checkNotNull(it) { "Rooms must have a `m.room.create` event." } }

val Room.name: Flow<NameContent?>
	get() = getState<NameContent>("m.room.name", "")

val Room.canonicalAlias: Flow<CanonicalAliasContent?>
	get() = getState<CanonicalAliasContent>("m.room.canonical_alias", "")

val Room.topic: Flow<TopicContent?>
	get() = getState<TopicContent>("m.room.topic", "")

val Room.avatar: Flow<AvatarContent?>
	get() = getState<AvatarContent>("m.room.avatar", "")

val Room.guestAccess: Flow<GuestAccessContent?>
	get() = getState<GuestAccessContent>("m.room.guest_access", "")

val Room.historyVisibility: Flow<HistoryVisibilityContent?>
	get() = getState<HistoryVisibilityContent>("m.room.history_visibility", "")

val Room.joinRules: Flow<JoinRulesContent?>
	get() = getState<JoinRulesContent>("m.room.join_rules", "")

val Room.pinnedEvents: Flow<PinnedEventsContent?>
	get() = getState<PinnedEventsContent>("m.room.pinned_events", "")

val Room.encryption: Flow<EncryptionContent?>
	get() = getState<EncryptionContent>("m.room.encryption", "")

val Room.powerLevels: Flow<PowerLevelsContent?>
	get() = getState<PowerLevelsContent>("m.room.power_levels", "")

val Room.serverAcl: Flow<ServerAclContent?>
	get() = getState<ServerAclContent>("m.room.server_acl", "")

val Room.tombstone: Flow<TombstoneContent?>
	get() = getState<TombstoneContent>("m.room.tombstone", "")


val Room.tags: Flow<Map<String, TagContent.Tag>>
	get() = getAccountData<TagContent>("m.tag")
		.map { it?.tags ?: emptyMap() }


private fun String.nullIfEmpty() = takeIf { it.isNotEmpty() }
private fun <E, T : Collection<E>> T.nullIfEmpty() = takeIf { it.isNotEmpty() }

@OptIn(ExperimentalCoroutinesApi::class)
private fun Room.getHeroes(): Flow<Set<String>> {
	val calculatedHeroes = joinedMembers.map { (it - ownUserId).nullIfEmpty() }
		.coalesce(invitedMembers.map { (it - ownUserId).nullIfEmpty() })
		// Fallback to left "members" when available
		.map { it?.sorted()?.take(5)?.toSet() }
		.coalesce(flowOf(emptySet()))
	return heroes.coalesce(calculatedHeroes)
}

@OptIn(ExperimentalCoroutinesApi::class)
fun Room.getDisplayName(): Flow<String> {
	fun memberDisplayNames(userIds: Collection<String>): Flow<List<String>> {
		val nameFlows = userIds.map { userId ->
			getState("m.room.member", userId, MemberContent.serializer())
				.map { it?.displayName ?: userId }
		}
		return combine(nameFlows) { it.toList() }
	}

	val roomName = name.map { it?.name?.nullIfEmpty() }
	val roomAlias = canonicalAlias.map { it?.alias?.nullIfEmpty() }

	data class CalcInfo(val heroes: Set<String>, val joinCount: Int, val inviteCount: Int)
	val roomMembers = combine(
		getHeroes(),
		joinedMemberCount,
		invitedMemberCount
	) { heroes, joinCount, inviteCount -> CalcInfo(heroes, joinCount, inviteCount) }
		.flatMapLatest { (heroes, joinCount, inviteCount) ->
			val otherMemberCount = (joinCount + inviteCount) - 1
			val roomNameFlow = if (heroes.size >= otherMemberCount) {
				if (heroes.isNotEmpty()) {
					memberDisplayNames(heroes.take(3)).map { names ->
						if (names.size > 1) {
							"${names.dropLast(1).joinToString()} and ${names.last()}"
						} else {
							names.single()
						}
					}
				} else {
					flowOf(null)
				}
			} else {
				if (joinCount + inviteCount > 1) {
					memberDisplayNames(heroes).map { names ->
						"${names.joinToString()} and ${otherMemberCount - names.size} others"
					}
				} else {
					// Spec doesn't say what to do here.
					flowOf(null)
				}
			}
			if (joinCount + inviteCount <= 1) {
				roomNameFlow.map { name ->
					if (heroes.isNotEmpty() && name != null) {
						"Empty room (was $name)"
					} else {
						"Empty room"
					}
				}
			} else {
				roomNameFlow
			}
		}

	return roomName.coalesce(roomAlias).coalesce(roomMembers).coalesce(flowOf("Empty Room"))
}

@OptIn(ExperimentalCoroutinesApi::class)
fun Room.getDisplayAvatar(): Flow<String?> {
	return avatar.map { it?.url }
		.coalesce(
			getHeroes().map { it.firstOrNull() }
				.flatMapLatest { userId ->
					if (userId != null) {
						getState("m.room.member", userId, MemberContent.serializer())
							.map { it?.avatarUrl }
					} else {
						flowOf(null)
					}
				})
}
