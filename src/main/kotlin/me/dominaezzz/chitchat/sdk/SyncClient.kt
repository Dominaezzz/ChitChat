package me.dominaezzz.chitchat.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

interface SyncClient {
	val joinedRooms: Flow<Map<String, Room>>

	fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?>
}
