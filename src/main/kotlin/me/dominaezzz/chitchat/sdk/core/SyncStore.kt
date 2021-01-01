package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.MessagesResponse
import io.github.matrixkt.models.events.contents.ReceiptContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.serialization.json.JsonObject

interface SyncStore {
	suspend fun getSyncToken(): String?
	suspend fun storeSync(sync: SyncResponse, token: String?)

	suspend fun getOneTimeKeysCount(): Map<String, Long>

	suspend fun getJoinedRooms(userId: String): Set<String>
	suspend fun getAccountData(type: String): JsonObject?
	suspend fun getUserDevice(userId: String, deviceId: String): Pair<DeviceKeys?, Boolean>?
	suspend fun getUserDevices(userId: String): Pair<List<DeviceKeys>, Boolean>?

	suspend fun getState(roomId: String, type: String, stateKey: String): JsonObject?
	suspend fun getAccountData(roomId: String, type: String): JsonObject?
	suspend fun getMembers(roomId: String, membership: Membership): Set<String>
	suspend fun getReadReceipts(roomId: String): List<ReadReceipt>

	suspend fun storeTimelineEvents(roomId: String, response: MessagesResponse): Int

	class ReadReceipt(val userId: String, val eventId: String, val receipt: ReceiptContent.Receipt)
}
