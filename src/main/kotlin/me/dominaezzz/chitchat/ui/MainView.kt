package me.dominaezzz.chitchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.events.contents.TagContent
import io.ktor.client.engine.apache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.models.AppViewModel
import me.dominaezzz.chitchat.ui.room.timeline.Conversation
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.util.IconCache
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI
import java.nio.file.*

val projectDir: Path = Paths.get("").toAbsolutePath()
val appWorkingDir: Path = projectDir.resolve("appdir")

val SessionAmbient = staticAmbientOf<LoginSession> { error("No login session provided") }
val ClientAmbient = staticAmbientOf<MatrixClient> { error("No client provided") }
val ContentRepoAmbient = staticAmbientOf<ContentRepository> { error("No content repo provided") }

@Composable
fun AppView() {
	val session = remember {
		usingConnection { conn ->
			LoginSession(
				accessToken = conn.getValue("ACCESS_TOKEN")!!,
				userId = conn.getValue("USER_ID")!!,
				deviceId = conn.getValue("DEVICE_ID")!!
			)
		}
	}
	val client = remember(session) {
		val engine = Apache.create {
			connectTimeout = 0
			socketTimeout = 0
		}
		MatrixClient(engine).apply {
			accessToken = session.accessToken
		}
	}
	val contentRepo = remember(client) { ContentRepository(client, appWorkingDir.resolve("media")) }

	Providers(SessionAmbient provides session, ClientAmbient provides client, ContentRepoAmbient provides contentRepo) {
		IconCache {
			MainView()
		}
	}
}

@Composable
fun MainView() {
	val client = ClientAmbient.current
	val session = SessionAmbient.current

	val appViewModel = remember { AppViewModel(client, session, appWorkingDir) }

	LaunchedEffect(appViewModel) {
		while (isActive) {
			try {
				appViewModel.sync()
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	val joinedRooms by remember { appViewModel.syncClient.joinedRooms }.collectAsState(emptyMap())
	var selectedRoom by remember { mutableStateOf<String?>(null) }

	Row(Modifier.fillMaxSize()) {
		RoomListView(
			joinedRooms.values,
			selectedRoom,
			{ selectedRoom = it },
			Modifier.fillMaxWidth(0.3f)
		)

		Box(
			Modifier.fillMaxHeight()
				.preferredWidth(1.dp)
				.background(color = Color.Black.copy(alpha = 0.27f))
		)

		if (selectedRoom != null) {
			val room by derivedStateOf { joinedRooms.getValue(selectedRoom!!) }
			RoomView(
				room,
				Modifier.fillMaxWidth()
			)
		}
	}
}

fun Collection<Room>.sortRooms(): Flow<List<Room>> {
	if (isEmpty()) {
		return flowOf(emptyList())
	}

	class RoomData(
		val room: Room,
		val displayName: String,
		val favourite: TagContent.Tag?,
		val lowPriority: TagContent.Tag?
	)

	val tagComparator = compareBy<TagContent.Tag> { it.order }
	val comparator = compareBy<RoomData> { 0 }
		.thenBy(nullsLast(tagComparator), { it.favourite })
		.thenBy(nullsFirst(tagComparator), { it.lowPriority })
		.thenBy(String.CASE_INSENSITIVE_ORDER, { it.displayName })

	val perRoomData = map { room ->
		combine(
			room.getDisplayName(),
			room.tags
		) { displayName, tags ->
			RoomData(
				room,
				displayName,
				tags["m.favourite"],
				tags["m.lowpriority"]
			)
		}
	}
	return combine(perRoomData) { roomData -> roomData.sortedWith(comparator).map { it.room } }
}

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
fun RoomListView(
	rooms: Collection<Room>,
	selectedRoom: String?,
	onSelectedRoomChanged: (String?) -> Unit,
	modifier: Modifier = Modifier
) {
	var roomFilter by remember { mutableStateOf("") }

	var showPublicRoomsPopup by remember { mutableStateOf(false) }

	if (showPublicRoomsPopup) {
		PublicRoomsPopup { showPublicRoomsPopup = false }
	}

	Column(modifier) {
		TopAppBar(
			title = {
				val session = SessionAmbient.current
				val client = ClientAmbient.current
				val username by produceState(session.userId, client) {
					val profile = client.userApi.getUserProfile(session.userId)
					val displayName = profile.displayName
					if (displayName != null) {
						value = displayName
					}
				}
				Text(username)
			},
			backgroundColor = Color.Transparent,
			actions = {
				IconButton(onClick = { /* Open Settings */ }, enabled = false) {
					Icon(Icons.Filled.Settings, null)
				}
			},
			elevation = 0.dp
			// navigationIcon = { Icon(Icons.Filled.Person) },
		)

		Spacer(Modifier.height(5.dp))

		Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
			Spacer(Modifier.width(5.dp))

			OutlinedTextField(
				value = roomFilter,
				onValueChange = { roomFilter = it },
				modifier = Modifier.weight(1f),
				placeholder = { Text("Filter...") },
				leadingIcon = { Icon(Icons.Filled.FilterList, null) }
			)

			IconButton(onClick = { showPublicRoomsPopup = true }) {
				Icon(Icons.Filled.Explore, null)
			}

			Spacer(Modifier.width(5.dp))
		}

		Text(
			"Rooms",
			Modifier.padding(10.dp).align(Alignment.CenterHorizontally),
			style = MaterialTheme.typography.h5
		)

		val sortedRooms = remember(rooms) { rooms.sortRooms() }.collectAsState(emptyList()).value
		LazyColumn {
			items(sortedRooms) { room ->
				val displayName = room.displayName()
				val displayAvatar = room.displayAvatar()

				@OptIn(ExperimentalAnimationApi::class)
				AnimatedVisibility(roomFilter.isEmpty() || displayName.contains(roomFilter, true)) {
					ListItem(
						modifier = Modifier.selectable(
							selected = selectedRoom == room.id,
							onClick = { onSelectedRoomChanged(room.id) }
						),
						text = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
						secondaryText = {
							val count by room.joinedMemberCount.collectAsState(0)
							Text("$count members")
						},
						singleLineSecondaryText = true,
						icon = {
							val image = displayAvatar?.let { loadIcon(URI(it)) }

							if (image != null) {
								Image(image, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
							} else {
								Image(Icons.Filled.Contacts, null, Modifier.size(40.dp))
							}
						}
					)
				}
			}
		}
	}
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

			Providers(AmbientContentAlpha provides ContentAlpha.high) {
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
				Providers(AmbientContentAlpha provides ContentAlpha.high) {
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
		val memberFlow = remember(userId) { room.getMember(userId) }
		val member by memberFlow.collectAsState(null)
		return member?.displayName ?: userId
	}

	val typingNotification = when (users.size) {
		1 -> "${getName(users.single())} is typing ..."
		2 -> "${getName(users[0])} and ${getName(users[1])} are typing ..."
		3 -> "${getName(users[0])}, ${getName(users[1])} and ${getName(users[2])} are typing ..."
		else -> "${getName(users[0])}, ${getName(users[1])} and ${users.size - 2} others are typing ..."
	}

	Providers(AmbientContentAlpha provides ContentAlpha.medium) {
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
		onImeActionPerformed = { _, _ -> /* Send message */ }
	)
}
