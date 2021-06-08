package me.dominaezzz.chitchat.ui.room

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.github.matrixkt.models.events.contents.room.PowerLevelsContent
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.ui.LocalAppModel
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
	var showMembers by remember { mutableStateOf(false) }

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

			IconToggleButton(showMembers, onCheckedChange = { showMembers = it }) {
				Icon(Icons.Filled.People, null)
			}

			IconButton(onClick = { /* Open room settings */ }, enabled = false) {
				Icon(Icons.Filled.Settings, null)
			}
		}

		Row(Modifier.weight(1f)) {
			Column(Modifier.weight(1f)) {
				// Timeline
				Conversation(room, Modifier.weight(1f))

				Spacer(Modifier.fillMaxWidth().height(8.dp))

				TypingUsers(room, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

				UserMessageInput(room.id, Modifier.fillMaxWidth())
			}

			if (showMembers) {
				Card(Modifier.width(300.dp).fillMaxHeight()) {
					RoomMembers(room, Modifier.fillMaxSize())
				}
			}
		}
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
	var draftMessage by rememberSaveable(roomId) { mutableStateOf("") }

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

@Composable
fun RoomMembers(
	room: Room,
	modifier: Modifier = Modifier
) {
	val appModel = LocalAppModel.current

	// Hack to trigger eager loading of members.
	LaunchedEffect(room) { room.joinedMembers.collect() }

	fun PowerLevelsContent.getUserPowerLevel(userId: String): Long {
		return users.getOrDefault(userId, usersDefault)
	}

	val powerLevels = remember(room) { room.powerLevels }.collectAsState(null).value

	val comparator = compareBy<Pair<String, MemberContent>> { powerLevels?.getUserPowerLevel(it.first) ?: 0 }
		.reversed()
		.thenBy(nullsLast()) { (_, member) -> member.displayName?.takeUnless { it.isBlank() } }
		.thenBy { (userId, _) -> userId }

	val members by remember(room, powerLevels) { appModel.getMemberList(room, comparator) }
		.collectAsState(emptyList())

	Column(modifier) {
		Row(Modifier.height(56.dp).padding(16.dp)) {
			Text("Members", style = MaterialTheme.typography.body2)
			Spacer(Modifier.weight(1f))
			CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
				val count by remember(room) { room.joinedMemberCount }.collectAsState(0)
				Text("$count", style = MaterialTheme.typography.caption)
			}
		}

		LazyColumn {
			items(members, key = { (userId, _) -> userId }) { (userId, member) ->
				val powerLevel = if (powerLevels != null) {
					when (powerLevels.getUserPowerLevel(userId)) {
						100L -> "Admin"
						50L -> "Moderator"
						else -> null
					}
				} else {
					null
				}

				@OptIn(ExperimentalMaterialApi::class)
				ListItem(
					icon = {
						val avatar = member.avatarUrl?.let { loadIcon(URI(it)) }
						if (avatar != null) {
							Image(
								avatar,
								null,
								Modifier.size(40.dp).clip(CircleShape),
								contentScale = ContentScale.Crop
							)
						} else {
							Image(Icons.Filled.Person, null, Modifier.size(40.dp))
						}
					},
					text = {
						Text(member.displayName ?: userId)
					},
					trailing = powerLevel?.let { { Text(it) } }
				)
			}
		}
	}
}
