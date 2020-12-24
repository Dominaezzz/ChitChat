package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.models.events.contents.TagContent
import io.github.matrixkt.models.events.contents.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import me.dominaezzz.chitchat.models.RoomTimeline

interface Room {
	val id: String

	val joinedMembers: Flow<Set<String>>
	val invitedMembers: Flow<Set<String>>

	fun <T> getState(type: String, stateKey: String, serializer: KSerializer<T>): Flow<T?>
	fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?>

	val create: Flow<CreateContent>

	val name: Flow<NameContent?>
	val topic: Flow<TopicContent?>
	val avatar: Flow<AvatarContent?>
	val canonicalAlias: Flow<CanonicalAliasContent?>
	val guestAccess: Flow<GuestAccessContent?>
	val historyVisibility: Flow<HistoryVisibilityContent?>
	val joinRules: Flow<JoinRulesContent?>
	val pinnedEvents: Flow<PinnedEventsContent?>
	val encryption: Flow<EncryptionContent?>
	val powerLevels: Flow<PowerLevelsContent?>
	val serverAcl: Flow<ServerAclContent?>
	val tombstone: Flow<TombstoneContent?>

	// fun <T> getMember(userId: String): Flow<MemberContent?>

	// val thirdPartyInvites: Flow<Map<String, ThirdPartyInviteContent>>
	// val accountData: Flow<Map<String, JsonObject>>

	val typingUsers: Flow<List<String>>

	val tags: Flow<Map<String, TagContent.Tag>>

	fun createTimelineView(): RoomTimeline
}
