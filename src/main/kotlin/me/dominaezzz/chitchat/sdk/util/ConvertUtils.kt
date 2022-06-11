package me.dominaezzz.chitchat.sdk.util

import io.github.matrixkt.clientserver.models.events.MatrixEvent
import io.github.matrixkt.clientserver.models.events.RoomEvent
import io.github.matrixkt.clientserver.models.events.SyncEvent

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

fun <Content : Any, UnsignedData> RoomEvent<Content, UnsignedData>.copy(
	type: String = this.type,
	content: Content = this.content,
	eventId: String = this.eventId,
	sender: String = this.sender,
	originServerTimestamp: Long = this.originServerTimestamp,
	unsigned: UnsignedData? = this.unsigned,
	roomId: String = this.roomId,
	stateKey: String? = this.stateKey,
	prevContent: Content? = this.prevContent
): RoomEvent<Content, UnsignedData> {
	return RoomEvent(type, content, eventId, sender, originServerTimestamp, unsigned, roomId, stateKey, prevContent)
}
