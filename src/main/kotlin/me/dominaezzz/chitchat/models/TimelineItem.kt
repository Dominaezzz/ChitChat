package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.events.MatrixEvent
import kotlinx.serialization.json.JsonObject

class TimelineItem(
	val event: MatrixEvent,
	val edits: List<JsonObject>,
	val reactions: Map<String, Int>
)
