package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.events.SyncEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.MemberContent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import me.dominaezzz.chitchat.models.RoomTimeline

interface Room {
	val id: String
	val ownUserId: String

	/**
	 * A flow of all new state events in this room.
	 */
	val stateEvents: Flow<SyncEvent>

	val timelineEvents: Flow<SyncEvent>

	val joinedMembers: Flow<Set<String>>
	val invitedMembers: Flow<Set<String>>

	val heroes: Flow<Set<String>?>
	val joinedMemberCount: Flow<Int>
	val invitedMemberCount: Flow<Int>

	val notificationCount: Flow<Int>
	val highlightCount: Flow<Int>

	fun <T> getState(type: String, stateKey: String, deserializer: DeserializationStrategy<T>): Flow<T?>
	fun <T> getAccountData(type: String, deserializer: DeserializationStrategy<T>): Flow<T?>

	fun getMember(userId: String): Flow<MemberContent?>

	val typingUsers: Flow<List<String>>

	fun getReadReceipts(eventId: String): Flow<Map<String, ReceiptContent.Receipt>>

	// This needs more thought/design.
	suspend fun backPaginate(eventId: String, limit: Int): Boolean
}

inline fun <reified T> Room.getState(type: String, stateKey: String): Flow<T?> {
	return getState(type, stateKey, serializer<T>())
}

inline fun <reified T> Room.getAccountData(type: String): Flow<T?> {
	return getAccountData(type, serializer<T>())
}
