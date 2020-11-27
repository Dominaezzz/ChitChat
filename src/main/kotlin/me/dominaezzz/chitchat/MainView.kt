package me.dominaezzz.chitchat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumnFor
import androidx.compose.foundation.lazy.LazyColumnForIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageAsset
import androidx.compose.ui.graphics.asImageAsset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.annotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.utils.MatrixJson
import io.ktor.client.engine.apache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.util.parseMatrixCustomHtml
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
	val iconLoader = remember(contentRepo) { IconLoader(contentRepo) }

	LaunchedEffect(appViewModel) {
		while (isActive) {
			try {
				appViewModel.sync()
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	var roomFilter by remember { mutableStateOf("") }
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
			TopAppBar(
				title = {
					val username by produceState("You", client) {
						// TODO: Get this from database.
						val userId = client.accountApi.getTokenOwner()
						value = userId
						val profile = client.userApi.getUserProfile(userId)
						value = profile.displayName ?: userId
					}
					Text(username)
				},
				backgroundColor = Color.Transparent,
				actions = {
					IconButton(onClick = { /* Open Settings */ }, enabled = false) {
						Icon(Icons.Filled.Settings)
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
					leadingIcon = { Icon(Icons.Filled.FilterList) }
				)

				IconButton(onClick = { /* Show public rooms */ }, enabled = false) {
					Icon(Icons.Filled.Explore)
				}

				Spacer(Modifier.width(5.dp))
			}

			Text(
				"Rooms",
				Modifier.padding(10.dp).align(Alignment.CenterHorizontally),
				style = MaterialTheme.typography.h5
			)

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
								runCatching {
									val url = URI(room.avatarUrl)
									value = iconLoader.loadIcon(url)
								}
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

		if (selectedRoom != null) {
			Column(Modifier.fillMaxWidth()) {
				TopAppBar(backgroundColor = Color.Transparent, elevation = 0.dp) {
					val room by derivedStateOf { rooms.single { it.id == selectedRoom } }

					Spacer(Modifier.width(16.dp))

					val image by produceState<ImageAsset?>(null, room) {
						value = null
						if (room.avatarUrl != null) {
							runCatching {
								val url = URI(room.avatarUrl)
								value = iconLoader.loadIcon(url)
							}
						}
					}

					if (image != null) {
						Image(image!!, Modifier.size(40.dp).clip(CircleShape).align(Alignment.CenterVertically), contentScale = ContentScale.Crop)
					} else {
						Image(Icons.Filled.Image, Modifier.size(40.dp).align(Alignment.CenterVertically))
					}

					Spacer(Modifier.width(24.dp))

					ProvideEmphasis(AmbientEmphasisLevels.current.high) {
						Text(
							text = room.displayName,
							modifier = Modifier.align(Alignment.CenterVertically),
							style = MaterialTheme.typography.h5,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					Spacer(Modifier.width(24.dp))

					val topic = room.topic
					if (topic != null) {
						ProvideEmphasis(AmbientEmphasisLevels.current.medium) {
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
						Icon(Icons.Filled.Settings)
					}
				}

				// Timeline
				LazyColumnForIndexed(timelineEvents, Modifier.weight(1f)) { idx, item ->
					if (idx == 0) {
						onActive {
							shouldBackPaginate.value = true
							onDispose {
								shouldBackPaginate.value = false
							}
						}
					}
					ChatItem(item, relevantMembers)
				}

				Spacer(Modifier.fillMaxWidth().height(8.dp))

				var draftMessage by remember(selectedRoom) { mutableStateOf("") }

				OutlinedTextField(
					value = draftMessage,
					onValueChange = { draftMessage = it },
					modifier = Modifier
						.fillMaxWidth()
						.padding(16.dp),
					placeholder = { Text("Send a message (unencrypted)...") },
					trailingIcon = {
						IconButton(onClick = { /* Send message */ }, enabled = false) {
							Icon(Icons.Filled.Send)
						}
					},
					onImeActionPerformed = { _, _ -> /* Send message */ }
				)
			}
		}
	}
}

@Composable
fun ChatItem(item: TimelineItem, members: Map<String, MemberContent>) {
	val event = item.event
	val sender = members.getValue(event.sender)
	when (event.type) {
		"m.room.message" -> {
			val content = MatrixJson.decodeFromJsonElement(MessageContent.serializer(), event.content)

			Column(Modifier.padding(start = 8.dp)) {
				// Author
				Text(
					text = sender.displayName ?: event.sender,
					style = MaterialTheme.typography.subtitle1
				)

				// Message
				when (content) {
					is MessageContent.Text -> {
						Surface(color = Color(0xFFF5F5F5), shape = RoundedCornerShape(0.dp, 8.dp, 8.dp, 8.dp)) {
							if (content.format == "org.matrix.custom.html") {
								val body = remember(content.formattedBody) {
									runCatching { parseMatrixCustomHtml(content.formattedBody!!) }
								}
								Text(
									text = body.getOrElse { AnnotatedString(content.body) },
									style = MaterialTheme.typography.body1,
									modifier = Modifier.padding(8.dp)
								)
							} else {
								Text(
									text = content.body,
									style = MaterialTheme.typography.body1,
									modifier = Modifier.padding(8.dp)
								)
							}
						}
					}
					is MessageContent.Redacted -> {
						Text("**This event was redacted**")
					}
					else -> {
						Text("This is a ${content::class.simpleName} message")
					}
				}
			}
		}
		"m.room.member" -> {
			val content = MatrixJson.decodeFromJsonElement(MemberContent.serializer(), event.content)
			val prevContent = event.prevContent?.let { MatrixJson.decodeFromJsonElement(MemberContent.serializer(), it) }

			val text = annotatedString {
				append(members[event.stateKey]?.displayName ?: event.stateKey ?: "Unknown user ")

				when (prevContent?.membership) {
					Membership.KNOCK -> TODO()
					Membership.BAN -> when (content.membership) {
						Membership.KNOCK -> TODO()
						Membership.INVITE, Membership.JOIN -> throw IllegalStateException("Must never happen")
						Membership.LEAVE -> append(" was unbanned")
						Membership.BAN -> append(" made no change")
					}
					Membership.LEAVE, null -> when (content.membership) {
						Membership.KNOCK -> TODO()
						Membership.INVITE -> append(" was invited")
						Membership.JOIN -> append(" joined")
						Membership.LEAVE -> append(" made no change")
						Membership.BAN -> append(" was banned")
					}
					Membership.JOIN -> when (content.membership) {
						Membership.KNOCK -> TODO()
						Membership.INVITE -> throw IllegalStateException("Must never happen")
						Membership.JOIN -> {
							val changedName = prevContent.displayName != content.displayName
							val changedAvatar = prevContent.avatarUrl != content.avatarUrl
							if (changedAvatar && changedName) {
								append(" changed their avatar and display name")
							} else if (changedAvatar) {
								append(" changed their avatar")
							} else if (changedName) {
								append(" changed display name")
							} else {
								append(" made no change")
							}
						}
						Membership.LEAVE -> append(if (event.stateKey == event.sender) " left" else " was kicked")
						Membership.BAN -> append(" was kicked and banned")
					}
					Membership.INVITE -> when (content.membership) {
						Membership.KNOCK -> TODO()
						Membership.INVITE -> append(" made no change")
						Membership.JOIN -> append(" joined")
						Membership.LEAVE -> append(if (event.stateKey == event.sender) " rejected invite" else "'s invitation was revoked")
						Membership.BAN -> append(" was banned")
					}
				}
			}
			ListItem {
				Text(text)
			}
		}
		"m.room.name" -> {
			val content = MatrixJson.decodeFromJsonElement(NameContent.serializer(), event.content)
			ListItem {
				Text("${sender.displayName ?: event.sender} updated the room name to '${content.name}'.")
			}
		}
		"m.room.topic" -> {
			val content = MatrixJson.decodeFromJsonElement(TopicContent.serializer(), event.content)
			ListItem {
				Text("${sender.displayName ?: event.sender} updated the topic to '${content.topic}'.")
			}
		}
		"m.room.avatar" -> {
			ListItem {
				Text("${sender.displayName ?: event.sender} updated the room avatar.")
			}
		}
		"m.room.canonical_alias" -> {
			val content = MatrixJson.decodeFromJsonElement(CanonicalAliasContent.serializer(), event.content)
			ListItem {
				Text("${sender.displayName ?: event.sender} set the room's canonical alias to '${content.alias}'.")
			}
		}
		"m.room.guest_access" -> {
			val content = MatrixJson.decodeFromJsonElement(GuestAccessContent.serializer(), event.content)
			ListItem {
				val action = when (content.guestAccess) {
					GuestAccess.CAN_JOIN -> "has allowed guests to join the room"
					GuestAccess.FORBIDDEN -> "disabled guest access"
				}
				Text("${sender.displayName ?: event.sender} ${action}.")
			}
		}
		"m.room.create" -> {
			val content = MatrixJson.decodeFromJsonElement(CreateContent.serializer(), event.content)
			ListItem {
				val text = buildString {
					append(members[content.creator]?.displayName ?: content.creator)
					append(" created this room")
					if (content.predecessor != null) {
						append(" to replace room '${content.predecessor?.roomId}'")
					}
				}
				Text(text)
			}
		}
		"m.room.join_rules" -> {
			val content = MatrixJson.decodeFromJsonElement(JoinRulesContent.serializer(), event.content)
			ListItem {
				val action = when (content.joinRule) {
					JoinRule.PUBLIC -> "has allowed anyone to join the room."
					JoinRule.PRIVATE -> "has allowed anyone to join the room if they know the roomId."
					JoinRule.INVITE -> "made the room invite only."
					JoinRule.KNOCK -> "has set the join rule to 'KNOCK'."
				}
				Text("${sender.displayName ?: event.sender} ${action}.")
			}
		}
		"m.room.history_visibility" -> {
			val content = MatrixJson.decodeFromJsonElement(HistoryVisibilityContent.serializer(), event.content)
			ListItem {
				val action = when (content.historyVisibility) {
					HistoryVisibility.INVITED -> "has set history visibility to 'INVITED'."
					HistoryVisibility.JOINED -> "has set history visibility to 'JOINED'."
					HistoryVisibility.SHARED -> "made future room history visible to all room members."
					HistoryVisibility.WORLD_READABLE -> "has set history visibility to 'WORLD_READABLE'."
				}
				Text("${sender.displayName ?: event.sender} ${action}.")
			}
		}
		"m.room.encrypted" -> {
			ListItem {
				Text("${sender.displayName ?: event.sender} has sent an encrypted message. E2EE not supported yet!")
			}
		}
		"m.room.encryption" -> {
			ListItem {
				Text("${sender.displayName ?: event.sender} has enabled End to End Encryption. E2EE not supported yet!")
			}
		}
		else -> {
			ListItem {
				Text("Cannot render '${event.type}' yet" )
			}
		}
	}
}
