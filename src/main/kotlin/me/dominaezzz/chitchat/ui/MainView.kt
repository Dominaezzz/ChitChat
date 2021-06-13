package me.dominaezzz.chitchat.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import me.dominaezzz.chitchat.models.AppModel
import me.dominaezzz.chitchat.ui.room.MemberCache
import me.dominaezzz.chitchat.ui.room.RoomView
import me.dominaezzz.chitchat.util.ImageCache
import java.nio.file.*

val projectDir: Path = Paths.get("").toAbsolutePath()
val appWorkingDir: Path = projectDir.resolve("appdir")

val LocalAppModel = staticCompositionLocalOf<AppModel> { error("No app model provided") }

@Composable
fun AppView() {
	val appModel = remember { AppModel(appWorkingDir) }

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

	Row(Modifier.fillMaxSize()) {
		RoomListView(
			joinedRooms.values,
			selectedRoom,
			{ selectedRoom = it },
			Modifier.fillMaxWidth(0.3f)
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
}
