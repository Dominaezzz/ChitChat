package me.dominaezzz.chitchat.models

import me.dominaezzz.chitchat.sdk.Room

class RoomHeader(
	val id: String,
	val displayName: String,
	val memberCount: Int,
	val avatarUrl: String?,
	val room: Room
)
