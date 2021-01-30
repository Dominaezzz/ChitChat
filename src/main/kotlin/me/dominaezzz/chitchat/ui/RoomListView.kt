package me.dominaezzz.chitchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matrixkt.models.events.contents.TagContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.sdk.core.getDisplayName
import me.dominaezzz.chitchat.sdk.core.tags
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

private fun Collection<Room>.sortRooms(): Flow<List<Room>> {
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
