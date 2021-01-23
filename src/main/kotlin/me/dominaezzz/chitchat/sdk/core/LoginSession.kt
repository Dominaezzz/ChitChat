package me.dominaezzz.chitchat.sdk.core

data class LoginSession(
	val accessToken: String,
	val userId: String,
	val deviceId: String
)
