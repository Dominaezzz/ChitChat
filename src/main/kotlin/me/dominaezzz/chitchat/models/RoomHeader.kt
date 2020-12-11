package me.dominaezzz.chitchat.models

class RoomHeader(
	val id: String,
	val displayName: String,
	val topic: String?,
	val memberCount: Int,
	val avatarUrl: String?,

	val firstEventsId: String,
	val prevLatestOrder: Int
)
