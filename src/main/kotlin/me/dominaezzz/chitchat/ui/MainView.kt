package me.dominaezzz.chitchat.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.*
import me.dominaezzz.chitchat.models.AppModel
import me.dominaezzz.chitchat.models.CreateRoomModel
import me.dominaezzz.chitchat.ui.room.MemberCache
import me.dominaezzz.chitchat.ui.room.RoomView
import me.dominaezzz.chitchat.util.ImageCache

val LocalAppModel = staticCompositionLocalOf<AppModel> { error("No app model provided") }

@Composable
fun AppView(appModel: AppModel) {
	CompositionLocalProvider(LocalAppModel provides appModel) {
		ImageCache {
			MainView()
		}
	}

	LaunchedEffect(appModel) {
		while (isActive) {
			try {
				appModel.sync()
			} catch (e: Exception) {
				if (e !is CancellationException) {
					e.printStackTrace()
				} else {
					throw e
				}
			}
		}
	}
}

@Composable
fun MainView() {
	val appViewModel = LocalAppModel.current

	val joinedRooms by remember { appViewModel.syncClient.joinedRooms }.collectAsState(emptyMap())
	var selectedRoom by remember { mutableStateOf<String?>(null) }
	val roomStateHolder = rememberSaveableStateHolder()

	var showPublicRoomsPopup by remember { mutableStateOf(false) }
	var showCreateRoomPopup by remember { mutableStateOf(false) }

	Row(Modifier.fillMaxSize()) {
		RoomListView(
			Modifier.fillMaxWidth(0.3f),
			joinedRooms.values,
			selectedRoom,
			{ selectedRoom = it },
			showCreateRoom = { showCreateRoomPopup = true },
			showPublicRooms = { showPublicRoomsPopup = true }
		)

		Box(
			Modifier.fillMaxHeight()
				.width(1.dp)
				.background(color = Color.Black.copy(alpha = 0.27f))
		)

		val room = selectedRoom?.let { joinedRooms[it] }
		if (room != null) {
			Crossfade(room) {
				roomStateHolder.SaveableStateProvider(it) {
					MemberCache(it) {
						RoomView(
							it,
							Modifier.fillMaxWidth()
						)
					}
				}
			}
		}
	}

	if (showPublicRoomsPopup) {
		Box(Modifier.fillMaxSize()) {
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
	}

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
}
