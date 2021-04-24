package me.dominaezzz.chitchat.sdk.util

import io.github.matrixkt.models.events.MatrixEvent
import io.github.matrixkt.models.events.SyncEvent

fun SyncEvent.toMatrixEvent(roomId: String): MatrixEvent {
	return MatrixEvent(
		type,
		content,
		eventId,
		sender,
		originServerTimestamp,
		unsigned,
		roomId,
		stateKey,
		prevContent
	)
}

fun MatrixEvent.toSyncEvent(): SyncEvent {
	return SyncEvent(
		type,
		content,
		eventId,
		sender,
		originServerTimestamp,
		unsigned,
		stateKey,
		prevContent
	)
}
