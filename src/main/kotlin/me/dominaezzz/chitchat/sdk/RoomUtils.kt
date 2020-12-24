package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.models.events.contents.room.MemberContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*


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
