package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.models.DeviceKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

interface SyncClient {
	val joinedRooms: Flow<Map<String, Room>>

	fun <T> getAccountData(type: String, serializer: KSerializer<T>): Flow<T?>

	suspend fun getUserDevices(userId: String): List<DeviceKeys>?
	suspend fun getUserDevice(userId: String, deviceId: String): DeviceKeys?
}
