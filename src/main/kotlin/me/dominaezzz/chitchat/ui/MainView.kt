package me.dominaezzz.chitchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matrixkt.MatrixClient
import kotlinx.coroutines.*
import me.dominaezzz.chitchat.models.AppModel
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.ui.room.MemberCache
import me.dominaezzz.chitchat.ui.room.RoomView
import me.dominaezzz.chitchat.util.ImageCache
import java.nio.file.*

val projectDir: Path = Paths.get("").toAbsolutePath()
val appWorkingDir: Path = projectDir.resolve("appdir")

val SessionAmbient = staticCompositionLocalOf<LoginSession> { error("No login session provided") }
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("No app model provided") }
val ClientAmbient = staticCompositionLocalOf<MatrixClient> { error("No client provided") }

@Composable
fun AppView() {
	val appModel = remember { AppModel(appWorkingDir) }

	CompositionLocalProvider(
		LocalAppModel provides appModel,
		SessionAmbient provides appModel.session,
		ClientAmbient provides appModel.client
	) {
		ImageCache {
			MainView()
		}
	}

	LaunchedEffect(appModel) {
		while (isActive) {
			try {
				appModel.sync()
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}
}

@Composable
fun MainView() {
	val appViewModel = LocalAppModel.current

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
				.width(1.dp)
				.background(color = Color.Black.copy(alpha = 0.27f))
		)

		val room = selectedRoom?.let { joinedRooms[it] }
		if (room != null) {
			MemberCache(room) {
				RoomView(
					room,
					Modifier.fillMaxWidth()
				)
			}
		}
	}
}
