package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.GetMembersResponse
import io.github.matrixkt.models.MessagesResponse
import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.RoomSummary
import io.github.matrixkt.models.sync.StrippedState
import io.github.matrixkt.models.sync.SyncResponse
import io.github.matrixkt.models.sync.UnreadNotificationCounts
import kotlinx.serialization.json.JsonObject

interface SyncStore {
	suspend fun getSyncToken(): String?
	suspend fun storeSync(sync: SyncResponse, token: String?)

	suspend fun getOneTimeKeysCount(): Map<String, Long>

	suspend fun getJoinedRooms(userId: String): Set<String>
	suspend fun getInvitations(): Map<String, List<StrippedState>>
	suspend fun getAccountData(type: String): JsonObject?

	suspend fun getState(roomId: String, type: String, stateKey: String): JsonObject?
	suspend fun getAccountData(roomId: String, type: String): JsonObject?
	suspend fun getMembers(roomId: String, membership: Membership): Set<String>
	suspend fun getReadReceipts(roomId: String): List<ReadReceipt>
	suspend fun getSummary(roomId: String): RoomSummary
	suspend fun getUnreadNotificationCounts(roomId: String): UnreadNotificationCounts
	suspend fun getLazyLoadingState(roomId: String): LazyLoadingState
	suspend fun storeMembers(roomId: String, response: GetMembersResponse): List<MatrixEvent>

	suspend fun getPaginationToken(roomId: String, eventId: String): String?
	suspend fun storeTimelineEvents(roomId: String, response: MessagesResponse): List<MatrixEvent>

	class ReadReceipt(val userId: String, val eventId: String, val receipt: ReceiptContent.Receipt)
	class LazyLoadingState(val token: String?, val loaded: Set<Membership>)
}
