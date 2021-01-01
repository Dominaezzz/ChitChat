package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.DeviceKeys
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.sync.SyncResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.KSerializer

interface SyncClient {
	val syncFlow: SharedFlow<SyncResponse>
	suspend fun sync(timeout: Long? = null, setPresence: Presence? = null)

	val joinedRooms: Flow<Map<String, Room>>

	fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?>

	suspend fun getUserDevices(userId: String): List<DeviceKeys>?
	suspend fun getUserDevice(userId: String, deviceId: String): DeviceKeys?
}
