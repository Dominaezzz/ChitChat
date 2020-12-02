package me.dominaezzz.chitchat.room.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumnForIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.flow.MutableStateFlow
import me.dominaezzz.chitchat.AppViewModel
import me.dominaezzz.chitchat.db.TimelineItem
import me.dominaezzz.chitchat.util.parseMatrixCustomHtml


private val AmbientMembers = ambientOf<Map<String, MemberContent>> { error("No members provided") }

@Composable
fun Conversation(
	roomId: String,
	appViewModel: AppViewModel,
	modifier: Modifier = Modifier
) {
	val timelineEvents = remember(roomId) { mutableStateListOf<TimelineItem>() }
	val relevantMembers = remember(roomId) { mutableStateMapOf<String, MemberContent>() }
	val shouldBackPaginate = remember(roomId) { MutableStateFlow(true) }

	LaunchedEffect(roomId) {
		appViewModel.selectRoom(roomId, timelineEvents, relevantMembers, shouldBackPaginate)
	}

	Row(modifier) {
		val state = rememberLazyListState(timelineEvents.size - 1)

		Providers(AmbientMembers provides relevantMembers) {
			LazyColumnForIndexed(timelineEvents, Modifier.weight(1f), state = state) { idx, item ->
				if (idx == 0) {
					onActive {
						shouldBackPaginate.value = true
						onDispose {
							shouldBackPaginate.value = false
						}
					}
				}
				ChatItem(item)
			}
		}

		Spacer(Modifier.width(8.dp))

		@OptIn(ExperimentalFoundationApi::class)
		VerticalScrollbar(
			rememberScrollbarAdapter(state, timelineEvents.size, 45.dp),
			Modifier.fillMaxHeight()
		)
	}
}

@Composable
fun ChatItem(item: TimelineItem) {
	if (item.event.type == "m.room.message") {
		MessageEvent(item)
		return
	}

	val members = AmbientMembers.current
	val event = item.event
	val sender = members.getValue(event.sender)
	when (event.type) {
		"m.room.member" -> {
			val content = MatrixJson.decodeFromJsonElement(MemberContent.serializer(), event.content)
			val prevContent = event.prevContent?.let { MatrixJson.decodeFromJsonElement(MemberContent.serializer(), it) }

			val text = buildAnnotatedString {
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

@Composable
private fun MessageEvent(item: TimelineItem) {
	val event = item.event
	val sender = AmbientMembers.current.getValue(event.sender)

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
