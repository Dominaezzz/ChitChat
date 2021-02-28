package me.dominaezzz.chitchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import io.github.matrixkt.models.events.contents.DirectContent
import io.github.matrixkt.models.events.contents.TagContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

private class RoomDetails(
	val room: Room,
	val displayName: String,
	val displayAvatar: String?,
	val memberCount: Int,
	val favourite: TagContent.Tag?,
	val lowPriority: TagContent.Tag?
)

private class RoomListModel(
	val favourite: List<RoomDetails> = emptyList(),
	val dm: List<RoomDetails> = emptyList(),
	val normal: List<RoomDetails> = emptyList(),
	val lowPriority: List<RoomDetails> = emptyList()
)

private fun Collection<Room>.partitionRooms(syncClient: SyncClient): Flow<RoomListModel> {
	if (isEmpty()) {
		return flowOf(RoomListModel())
	}

	val nameComparator = compareBy<RoomDetails, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }

	val perRoomData = map { room ->
		combine(
			room.getDisplayName(),
			room.getDisplayAvatar(),
			room.joinedMemberCount,
			room.tags
		) { displayName, displayAvatar, memberCount, tags ->
			RoomDetails(
				room,
				displayName,
				displayAvatar,
				memberCount,
				tags["m.favourite"],
				tags["m.lowpriority"]
			)
		}
	}
	val allRoomData = combine(perRoomData) { it }
	val directContent = syncClient.getAccountData("m.direct", DirectContent.serializer())
		.map { it?.values?.flatten()?.toSet().orEmpty() }

	return combine(allRoomData, directContent) { roomData, dms ->
		val (favouriteRoomData, remaining1) = roomData.partition { it.favourite != null }
		val (lowPriorityRoomData, remaining2) = remaining1.partition { it.lowPriority != null }
		val (dmRooms, otherRoomData) = remaining2.partition { it.room.id in dms }
		RoomListModel(
			favouriteRoomData
				.sortedWith(compareBy<RoomDetails> { it.favourite!!.order }
					.then(nameComparator)),
			dmRooms.sortedWith(nameComparator),
			otherRoomData.sortedWith(nameComparator),
			lowPriorityRoomData
				.sortedWith(compareBy<RoomDetails> { it.lowPriority!!.order }
					.then(nameComparator))
		)
	}
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

		Spacer(Modifier.height(5.dp))

		val syncClient = AppModelAmbient.current.syncClient
		val model = remember(rooms) { rooms.partitionRooms(syncClient) }.collectAsState(RoomListModel()).value
		LazyColumn {
			fun section(header: String, rooms: List<RoomDetails>) {
				@OptIn(ExperimentalFoundationApi::class)
				stickyHeader {
					Text(
						header,
						Modifier.fillMaxWidth()
							.background(Color.LightGray)
							.padding(horizontal = 16.dp, vertical = 8.dp),
						style = MaterialTheme.typography.subtitle1
					)
				}

				items(rooms) { roomDetail ->
					val room = roomDetail.room
					val displayName = roomDetail.displayName
					val displayAvatar = roomDetail.displayAvatar

					@OptIn(ExperimentalAnimationApi::class)
					AnimatedVisibility(roomFilter.isEmpty() || displayName.contains(roomFilter, true)) {
						@OptIn(ExperimentalMaterialApi::class)
						ListItem(
							modifier = Modifier.selectable(
								selected = selectedRoom == room.id,
								onClick = { onSelectedRoomChanged(room.id) }
							),
							text = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
							secondaryText = { Text("${roomDetail.memberCount} members") },
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

			section("Favourites", model.favourite)
			section("Direct Messages", model.dm)
			section("Rooms", model.normal)
			section("Low Priority", model.lowPriority)
		}
	}
}
