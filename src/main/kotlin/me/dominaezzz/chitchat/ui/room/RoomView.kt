package me.dominaezzz.chitchat.ui.room

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.getDisplayAvatar
import me.dominaezzz.chitchat.sdk.core.getDisplayName
import me.dominaezzz.chitchat.sdk.core.topic
import me.dominaezzz.chitchat.ui.room.timeline.Conversation
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

@Composable
fun Room.displayName(): String {
	val name = remember(this) { getDisplayName() }.collectAsState(id)
	return name.value
}

@Composable
fun Room.displayAvatar(): String? {
	val avatar = remember(this) { getDisplayAvatar() }.collectAsState(null)
	return avatar.value
}

@Composable
fun RoomView(
	room: Room,
	modifier: Modifier = Modifier
) {
	Column(modifier) {
		TopAppBar(backgroundColor = Color.Transparent, elevation = 0.dp) {

			Spacer(Modifier.width(16.dp))

			val image = room.displayAvatar()?.let { loadIcon(URI(it)) }

			if (image != null) {
				Image(
					image,
					null,
					Modifier.size(40.dp).clip(CircleShape).align(Alignment.CenterVertically),
					contentScale = ContentScale.Crop
				)
			} else {
				Image(Icons.Filled.Image, null, Modifier.size(40.dp).align(Alignment.CenterVertically))
			}

			Spacer(Modifier.width(24.dp))

			CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.high) {
				Text(
					text = room.displayName(),
					modifier = Modifier.align(Alignment.CenterVertically),
					style = MaterialTheme.typography.h5,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}

			Spacer(Modifier.width(24.dp))

			val topic = room.topic.collectAsState(null).value?.topic
			if (topic != null) {
				CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.high) {
					Text(
						text = topic,
						modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
						style = MaterialTheme.typography.body2,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}
			} else {
				Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
			}

			IconButton(onClick = { /* Open room settings */ }, enabled = false) {
				Icon(Icons.Filled.Settings, null)
			}
		}

		// Timeline
		Conversation(room, Modifier.weight(1f))

		Spacer(Modifier.fillMaxWidth().height(8.dp))

		TypingUsers(room, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

		UserMessageInput(room.id, Modifier.fillMaxWidth())
	}
}

@Composable
fun TypingUsers(
	room: Room,
	modifier: Modifier = Modifier
) {
	val users by room.typingUsers.collectAsState(emptyList())
	if (users.isEmpty()) return

	@Composable
	fun getName(userId: String): String {
		val member = getMember(room, userId).value
		return member?.displayName ?: userId
	}

	val typingNotification = when (users.size) {
		1 -> "${getName(users.single())} is typing ..."
		2 -> "${getName(users[0])} and ${getName(users[1])} are typing ..."
		3 -> "${getName(users[0])}, ${getName(users[1])} and ${getName(users[2])} are typing ..."
		else -> "${getName(users[0])}, ${getName(users[1])} and ${users.size - 2} others are typing ..."
	}

	CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
		Text(typingNotification, modifier)
	}
}

@Composable
fun UserMessageInput(
	roomId: String,
	modifier: Modifier = Modifier
) {
	var draftMessage by remember(roomId) { mutableStateOf("") }

	OutlinedTextField(
		value = draftMessage,
		onValueChange = { draftMessage = it },
		modifier = modifier.padding(16.dp),
		placeholder = { Text("Send a message...") },
		trailingIcon = {
			IconButton(onClick = { /* Send message */ }, enabled = false) {
				Icon(Icons.Filled.Send, null)
			}
		},
		keyboardActions = KeyboardActions(onSend = { /* Send message */ })
	)
}
