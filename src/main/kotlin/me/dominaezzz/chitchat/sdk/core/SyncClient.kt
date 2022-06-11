package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.clientserver.models.sync.SyncResponse
import io.github.matrixkt.events.Presence
import io.github.matrixkt.events.StrippedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

interface SyncClient {
	val syncFlow: SharedFlow<SyncResponse>
	suspend fun sync(timeout: Long? = null, setPresence: Presence? = null)

	val oneTimeKeysCount: Flow<Map<String, Long>>

	val joinedRooms: Flow<Map<String, Room>>
	val invitedRooms: Flow<Map<String, List<StrippedState>>>

	fun <T> getAccountData(type: String, deserializer: DeserializationStrategy<T>): Flow<T?>
}

inline fun <reified T> SyncClient.getAccountData(type: String): Flow<T?> {
	return getAccountData(type, serializer<T>())
}
