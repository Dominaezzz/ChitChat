package me.dominaezzz.chitchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.github.matrixkt.api.GetUserProfile
import io.github.matrixkt.models.events.contents.DirectContent
import io.github.matrixkt.models.events.contents.TagContent
import io.github.matrixkt.utils.rpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.models.CreateRoomModel
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
		Popup(
			alignment = Alignment.Center,
			onDismissRequest = { showPublicRoomsPopup = false },
			focusable = true
		) {
			Card(
				modifier = Modifier.fillMaxSize(0.7f),
				elevation = 20.dp
			) {
				PublicRooms()
			}
		}
	}

	var showCreateRoomPopup by remember { mutableStateOf(false) }
	if (showCreateRoomPopup) {
		val appModel = LocalAppModel.current
		Popup(
			alignment = Alignment.Center,
			onDismissRequest = { /* Don't want user to accidentally close dialog */ },
			focusable = true
		) {
			Card(elevation = 24.dp) {
				val scope = rememberCoroutineScope()
				val model = remember { CreateRoomModel(scope, appModel.client, appModel.session) }
				val status by model.createStatus.collectAsState()

				Box(Modifier.animateContentSize()) {
					when (status) {
						is CreateRoomModel.Status.Creating -> {
							CircularProgressIndicator(Modifier.padding(8.dp))
						}
						is CreateRoomModel.Status.Created -> {
							CircularProgressIndicator(Modifier.padding(8.dp))
							SideEffect {
								showCreateRoomPopup = false
							}
						}
						is CreateRoomModel.Status.Failed, null -> {
							CreateRoomView(
								model,
								onCreateClicked = { model.createRoom() },
								onCancelClicked = { showCreateRoomPopup = false }
							)
						}
					}
				}
			}
		}
	}

	Column(modifier) {
		TopAppBar(
			title = {
				val session = LocalAppModel.current.session
				val client = LocalAppModel.current.client
				val username by produceState(session.userId, client) {
					val request = GetUserProfile(GetUserProfile.Url(session.userId))
					val profile = client.rpc(request)
					val displayName = profile.displayname
					if (displayName != null) {
						value = displayName
					}
				}
				Text(username)
			},
			backgroundColor = Color.Transparent,
			actions = {
				val syncClient = LocalAppModel.current.syncClient
				@OptIn(ExperimentalCoroutinesApi::class)
				val syncCount by remember(syncClient) {
					syncClient.syncFlow
						.distinctUntilChangedBy { it.nextBatch }
						.map { 1 }
						.runningReduce { acc, value -> acc + value }
				}.collectAsState(0)

				Text(
					text = syncCount.toString(),
					modifier = Modifier
						.align(Alignment.CenterVertically)
						.padding(4.dp)
						.border(2.dp, Color.Cyan, CircleShape)
						.size(24.dp),
					textAlign = TextAlign.Center
				)

				IconButton(onClick = { /* Open Settings */ }, enabled = false) {
					Icon(Icons.Filled.Settings, null)
				}
			},
			elevation = 0.dp
			// navigationIcon = { Icon(Icons.Filled.Person) },
		)

		Spacer(Modifier.height(5.dp))

		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			OutlinedTextField(
				value = roomFilter,
				onValueChange = { roomFilter = it },
				modifier = Modifier.weight(1f),
				placeholder = { Text("Filter...") },
				leadingIcon = { Icon(Icons.Filled.FilterList, null) }
			)

			IconButton(onClick = { showCreateRoomPopup = true }) {
				Icon(Icons.Filled.Add, null)
			}

			IconButton(onClick = { showPublicRoomsPopup = true }) {
				Icon(Icons.Filled.Explore, null)
			}
		}

		Spacer(Modifier.height(5.dp))

		val syncClient = LocalAppModel.current.syncClient
		val model by remember(rooms) { rooms.partitionRooms(syncClient) }.collectAsState(RoomListModel())
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

				items(rooms, key = { it.room.id }) { roomDetail ->
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
