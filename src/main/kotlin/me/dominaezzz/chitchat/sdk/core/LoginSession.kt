package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.clientserver.models.wellknown.DiscoveryInformation

data class LoginSession(
	val accessToken: String,
	val userId: String,
	val deviceId: String,
	val discoveryInfo: DiscoveryInformation
)
