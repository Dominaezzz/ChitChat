package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.api.GetMembersByRoom
import io.github.matrixkt.api.GetRoomEvents
import io.github.matrixkt.models.events.StrippedState
import io.github.matrixkt.models.events.SyncEvent
import io.github.matrixkt.models.events.SyncStateEvent
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.RoomSummary
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
	suspend fun getState(roomId: String, type: String): Map<String, JsonObject>
	suspend fun getAccountData(roomId: String, type: String): JsonObject?
	suspend fun getMembers(roomId: String, membership: Membership): Set<String>
	suspend fun getReadReceipts(roomId: String, eventId: String): Map<String, ReceiptContent.Receipt>
	suspend fun getSummary(roomId: String): RoomSummary
	suspend fun getUnreadNotificationCounts(roomId: String): UnreadNotificationCounts
	suspend fun getLazyLoadingState(roomId: String): LazyLoadingState
	suspend fun storeMembers(roomId: String, response: GetMembersByRoom.Response): List<SyncStateEvent>

	suspend fun getPaginationToken(roomId: String, eventId: String): String?
	suspend fun storeTimelineEvents(roomId: String, response: GetRoomEvents.Response): List<SyncStateEvent>

	class LazyLoadingState(val token: String?, val loaded: Set<Membership>)
}
