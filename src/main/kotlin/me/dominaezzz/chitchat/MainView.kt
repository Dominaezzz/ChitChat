package me.dominaezzz.chitchat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumnFor
import androidx.compose.foundation.lazy.LazyColumnForIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageAsset
import androidx.compose.ui.graphics.asImageAsset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.ktor.client.engine.apache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import me.dominaezzz.chitchat.db.*
import org.jetbrains.skija.Image
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

val projectDir: Path = Paths.get("").toAbsolutePath()
val appWorkingDir: Path = projectDir.resolve("appdir")
val databaseWriteSemaphore = Semaphore(1)


val ClientAmbient = staticAmbientOf<MatrixClient> { error("No client provided") }
val ContentRepoAmbient = staticAmbientOf<ContentRepository> { error("No content repo provided") }
val DatabaseSemaphoreAmbient = staticAmbientOf<Semaphore> { error("No database semaphore provided") }

@Composable
fun AppView() {
	val client = remember {
		val engine = Apache.create {
			connectTimeout = 0
			socketTimeout = 0
		}
		MatrixClient(engine).apply {
			accessToken = usingConnection { it.getValue("ACCESS_TOKEN")!! }
		}
	}
	val contentRepo = remember(client) { ContentRepository(client, appWorkingDir.resolve("media")) }

	Providers(ClientAmbient provides client, ContentRepoAmbient provides contentRepo) {
		MainView()
	}
}

@Composable
fun MainView() {
	val client = ClientAmbient.current
	val contentRepo = ContentRepoAmbient.current

	val appViewModel = remember { AppViewModel(client, databaseWriteSemaphore) }

	LaunchedEffect(appViewModel) {
		while (isActive) {
			try {
				appViewModel.sync()
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	val rooms = remember { mutableStateListOf<AppViewModel.Room>() }
	LaunchedEffect(appViewModel) { appViewModel.rooms(rooms) }
	var selectedRoom by remember { mutableStateOf<String?>(null) }

	val timelineEvents = remember { mutableStateListOf<TimelineItem>() }
	val relevantMembers = remember { mutableStateMapOf<String, MemberContent>() }
	val shouldBackPaginate = remember { MutableStateFlow(true) }

	LaunchedEffect(selectedRoom) {
		val roomId = selectedRoom ?: return@LaunchedEffect

		appViewModel.selectRoom(roomId, timelineEvents, relevantMembers, shouldBackPaginate)
	}

	Row(Modifier.fillMaxSize()) {
		Column(Modifier.fillMaxWidth(0.3f)) {
			Text(
				"Rooms",
				Modifier.padding(10.dp).align(Alignment.CenterHorizontally),
				style = MaterialTheme.typography.h5
			)

			Spacer(Modifier.height(5.dp))

			LazyColumnFor(rooms) { room ->
				ListItem(
					modifier = Modifier.selectable(
						selected = selectedRoom == room.id,
						onClick = { selectedRoom = room.id }
					),
					text = { Text(room.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
					secondaryText = { Text("${room.memberCount} members") },
					singleLineSecondaryText = true,
					icon = {
						val image by produceState<ImageAsset?>(null, room) {
							value = null
							if (room.avatarUrl != null) {
								val url = URI(room.avatarUrl)
								val data = contentRepo.getContent(url)
								val image = withContext(Dispatchers.Default) {
									Image.makeFromEncoded(data).asImageAsset()
								}
								value = image
							}
						}

						if (image != null) {
							Image(image!!, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
						} else {
							Image(Icons.Filled.Contacts, Modifier.size(40.dp))
						}
					}
				)
			}
		}

		Box(
			Modifier.fillMaxHeight()
				.preferredWidth(1.dp)
				.background(color = Color.Black.copy(alpha = 0.27f))
		)

		// Timeline
		LazyColumnForIndexed(timelineEvents, Modifier.fillMaxSize()) { idx, item ->
			if (idx == 0) {
				onActive {
					shouldBackPaginate.value = true
					onDispose {
						shouldBackPaginate.value = false
					}
				}
			}
			ListItem(
				text = { Text(item.event.type) },
				secondaryText = { Text(item.event.content.toString()) }
			)
		}
	}
}
