package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.models.DeviceKeys

interface DeviceStore {
	suspend fun getUserDevice(userId: String, deviceId: String): Pair<DeviceKeys?, Boolean>?
	suspend fun getUserDevices(userId: String): Pair<List<DeviceKeys>, Boolean>?
}
