package me.dominaezzz.chitchat.sdk.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MegolmPayload(
	val type: String,
	val content: JsonObject,
	@SerialName("room_id")
	val roomId: String
)
