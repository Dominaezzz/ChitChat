package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.events.contents.TagContent
import me.dominaezzz.chitchat.sdk.core.Room

class RoomHeader(
	val id: String,
	val room: Room,
	val displayName: String,
	val memberCount: Int,
	val avatarUrl: String?,
	val favourite: TagContent.Tag?,
	val lowPriority: TagContent.Tag?
)
