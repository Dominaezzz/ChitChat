package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import me.dominaezzz.chitchat.models.RoomTimeline

interface Room {
	val id: String

	val timelineEvents: Flow<MatrixEvent>

	val joinedMembers: Flow<Set<String>>
	val invitedMembers: Flow<Set<String>>

	fun <T> getState(type: String, stateKey: String, serializer: KSerializer<T>): Flow<T?>
	fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?>

	// fun <T> getMember(userId: String): Flow<MemberContent?>

	val typingUsers: Flow<List<String>>

	// Needs more thought about what data structure to expose here.
	val readReceipts: Flow<Map<String, List<Pair<String, ReceiptContent.Receipt>>>>

	fun createTimelineView(): RoomTimeline
}
