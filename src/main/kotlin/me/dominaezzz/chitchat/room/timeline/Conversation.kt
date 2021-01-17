package me.dominaezzz.chitchat.room.timeline

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonNull
import me.dominaezzz.chitchat.db.TimelineItem
import me.dominaezzz.chitchat.sdk.core.Room
import me.dominaezzz.chitchat.util.loadIcon
import me.dominaezzz.chitchat.util.loadImage
import me.dominaezzz.chitchat.util.formatting.parseMatrixCustomHtml
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


@Composable
fun Conversation(
	room: Room,
	modifier: Modifier = Modifier
) {
	val timeline = remember(room) { room.createTimelineView() }
	LaunchedEffect(timeline) { timeline.run() }
	val shouldBackPaginate = timeline.shouldBackPaginate

	@OptIn(ExperimentalCoroutinesApi::class)
	val timelineEvents by timeline.events.collectAsState().let { derivedStateOf { it.value.asReversed() } }

	Row(modifier) {
		val state = rememberLazyListState()

		LazyColumn(Modifier.weight(1f), state = state, reverseLayout = true) {
			itemsIndexed(timelineEvents) { idx, item ->
				if (idx == timelineEvents.lastIndex) {
					DisposableEffect(item.event.eventId) {
						shouldBackPaginate.value = true
						onDispose {
							shouldBackPaginate.value = false
						}
					}
				}

				Column {
					if (item.event.type == "m.room.message") {
						val sender = item.event.sender
						val prev = timelineEvents.getOrNull(idx + 1)?.event
						val next = timelineEvents.getOrNull(idx - 1)?.event
						val isNotFirst = prev != null && prev.sender == sender && prev.type == "m.room.message"
						val isNotLast = next != null && next.sender == sender && next.type == "m.room.message"
						MessageEvent(room, item, !isNotFirst, !isNotLast)
					} else {
						ChatItem(room, item)
					}

					ReadReceipts(room, item.event.eventId, Modifier.align(Alignment.End))
				}
			}
		}

		Spacer(Modifier.width(8.dp))

		@OptIn(ExperimentalFoundationApi::class)
		VerticalScrollbar(
			rememberScrollbarAdapter(state, timelineEvents.size, 72.dp),
			Modifier.fillMaxHeight()
		)
	}
}

@Composable
private fun Room.member(userId: String): MemberContent? {
	return remember(this, userId) { getState("m.room.member", userId, MemberContent.serializer()) }
		.collectAsState(null).value
}

@Composable
private fun ChatItem(room: Room, item: TimelineItem) {
	val event = item.event
	val sender = room.member(event.sender)

	val text = when (event.type) {
		"m.room.member" -> {
			val content = MatrixJson.decodeFromJsonElement(MemberContent.serializer(), event.content)
			val prevContentJson = event.prevContent ?: event.unsigned?.get("prev_content") ?: JsonNull
			val prevContent = MatrixJson.decodeFromJsonElement(MemberContent.serializer().nullable, prevContentJson)
			val target = room.member(event.stateKey ?: return)

			buildAnnotatedString {
				append(target?.displayName ?: event.stateKey ?: "Unknown user ")

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
		}
		"m.room.name" -> {
			val content = MatrixJson.decodeFromJsonElement(NameContent.serializer(), event.content)
			AnnotatedString("${sender?.displayName ?: event.sender} updated the room name to '${content.name}'.")
		}
		"m.room.topic" -> {
			val content = MatrixJson.decodeFromJsonElement(TopicContent.serializer(), event.content)
			AnnotatedString("${sender?.displayName ?: event.sender} updated the topic to '${content.topic}'.")
		}
		"m.room.avatar" -> {
			AnnotatedString("${sender?.displayName ?: event.sender} updated the room avatar.")
		}
		"m.room.canonical_alias" -> {
			val content = MatrixJson.decodeFromJsonElement(CanonicalAliasContent.serializer(), event.content)
			AnnotatedString("${sender?.displayName ?: event.sender} set the room's canonical alias to '${content.alias}'.")
		}
		"m.room.guest_access" -> {
			val content = MatrixJson.decodeFromJsonElement(GuestAccessContent.serializer(), event.content)
			val action = when (content.guestAccess) {
				GuestAccess.CAN_JOIN -> "has allowed guests to join the room"
				GuestAccess.FORBIDDEN -> "disabled guest access"
			}
			AnnotatedString("${sender?.displayName ?: event.sender} ${action}.")
		}
		"m.room.create" -> {
			val content = MatrixJson.decodeFromJsonElement(CreateContent.serializer(), event.content)
			buildAnnotatedString {
				val creator = room.member(content.creator)
				append(creator?.displayName ?: content.creator)
				append(" created this room")
				if (content.predecessor != null) {
					append(" to replace room '${content.predecessor?.roomId}'")
				}
			}
		}
		"m.room.join_rules" -> {
			val content = MatrixJson.decodeFromJsonElement(JoinRulesContent.serializer(), event.content)
			val action = when (content.joinRule) {
				JoinRule.PUBLIC -> "has allowed anyone to join the room."
				JoinRule.PRIVATE -> "has allowed anyone to join the room if they know the roomId."
				JoinRule.INVITE -> "made the room invite only."
				JoinRule.KNOCK -> "has set the join rule to 'KNOCK'."
			}
			AnnotatedString("${sender?.displayName ?: event.sender} ${action}.")
		}
		"m.room.history_visibility" -> {
			val content = MatrixJson.decodeFromJsonElement(HistoryVisibilityContent.serializer(), event.content)
			val action = when (content.historyVisibility) {
				HistoryVisibility.INVITED -> "has set history visibility to 'INVITED'."
				HistoryVisibility.JOINED -> "has set history visibility to 'JOINED'."
				HistoryVisibility.SHARED -> "made future room history visible to all room members."
				HistoryVisibility.WORLD_READABLE -> "has set history visibility to 'WORLD_READABLE'."
			}
			AnnotatedString("${sender?.displayName ?: event.sender} ${action}.")
		}
		"m.room.encrypted" -> {
			AnnotatedString("${sender?.displayName ?: event.sender} has sent an encrypted message. E2EE not supported yet!")
		}
		"m.room.encryption" -> {
			AnnotatedString("${sender?.displayName ?: event.sender} has enabled End to End Encryption. E2EE not supported yet!")
		}
		else -> {
			AnnotatedString("Cannot render '${event.type}' yet" )
		}
	}
	ListItem {
		Text(text)
	}
}

@Composable
private fun ReadReceipts(room: Room, eventId: String, modifier: Modifier = Modifier) {
	val roomReceipts by room.readReceipts.collectAsState(emptyMap())
	val limit = 10

	val eventReceipts = roomReceipts[eventId]
	if (!eventReceipts.isNullOrEmpty()) {
		Row(
			modifier.padding(4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			if (eventReceipts.size > limit) {
				Providers(AmbientContentAlpha provides ContentAlpha.medium) {
					Text(
						"${eventReceipts.size - limit}+",
						style = MaterialTheme.typography.caption
					)
				}
			}

			for ((userId, _) in eventReceipts.take(limit)) {
				Spacer(Modifier.width(1.dp))

				val member = room.member(userId)
				val avatar = member?.avatarUrl?.let { loadIcon(URI(it)) }

				@Suppress("NAME_SHADOWING")
				val modifier = Modifier.preferredSize(16.dp)
					.clip(CircleShape)
				if (avatar != null) {
					Image(avatar, modifier, contentScale = ContentScale.Crop)
				} else {
					Image(Icons.Filled.Person, modifier, contentScale = ContentScale.Crop)
				}
			}
		}
	}
}

@Composable
private fun MessageEvent(room: Room, item: TimelineItem, isFirstByAuthor: Boolean, isLastByAuthor: Boolean) {
	val event = item.event

	Row(Modifier.padding(top = if(isFirstByAuthor) 8.dp else 0.dp)) {
		// Render author image on the left
		if (isFirstByAuthor) {
			val sender = room.member(event.sender)
			val authorAvatar = sender?.avatarUrl?.let { loadIcon(URI(it)) }

			val modifier = Modifier.padding(horizontal = 16.dp)
				.preferredSize(42.dp)
				.clip(CircleShape)
				.align(Alignment.Top)
			if (authorAvatar != null) {
				Image(authorAvatar, modifier, contentScale = ContentScale.Crop)
			} else {
				Image(Icons.Filled.Person, modifier, contentScale = ContentScale.Crop)
			}
		} else {
			Spacer(Modifier.preferredWidth(74.dp))
		}

		// Render message on the right
		Column(Modifier.weight(1f)) {
			if (isFirstByAuthor) {
				AuthorAndTimeStamp(room, event.sender, event.originServerTimestamp)
			}

			val content = MatrixJson.decodeFromJsonElement(MessageContent.serializer(), event.content)
			Message(room, content, event.sender)

			if (isLastByAuthor) {
				Spacer(Modifier.preferredHeight(8.dp))
			} else {
				Spacer(Modifier.preferredHeight(4.dp))
			}
		}
	}
}

@Composable
private fun AuthorAndTimeStamp(room: Room, senderUserId: String, originServerTimestamp: Long) {
	val sender = room.member(senderUserId)

	Row {
		Text(
			text = sender?.displayName ?: senderUserId,
			style = MaterialTheme.typography.subtitle1,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.alignBy(LastBaseline)
				.paddingFrom(LastBaseline, after = 8.dp) // Space to 1st bubble
		)
		Spacer(Modifier.preferredWidth(8.dp))
		Providers(AmbientContentAlpha provides ContentAlpha.medium) {
			// TODO: Get ZoneId from compose and watch for system changes
			Text(
				text = Instant.ofEpochMilli(originServerTimestamp).atZone(ZoneId.systemDefault())
					.format(DateTimeFormatter.ofPattern("HH:mm")),
				style = MaterialTheme.typography.caption,
				modifier = Modifier.alignBy(LastBaseline)
			)
		}
	}
}

@Composable
private fun Message(room: Room, content: MessageContent, senderUserId: String) {
	@Composable
	fun formatting(format: String?, formattedBody: String?): AnnotatedString {
		return if (format == "org.matrix.custom.html") {
			val typography = MaterialTheme.typography
			try {
				parseMatrixCustomHtml(formattedBody!!, typography)
			} catch (e: Exception) {
				AnnotatedString(content.body)
			}
		} else {
			AnnotatedString(content.body)
		}
	}

	when (content) {
		is MessageContent.Text -> {
			Text(
				text = formatting(content.format, content.formattedBody),
				style = MaterialTheme.typography.body1
			)
		}
		is MessageContent.Notice -> {
			Providers(AmbientContentAlpha provides ContentAlpha.medium) {
				Text(
					text = formatting(content.format, content.formattedBody),
					style = MaterialTheme.typography.body1
				)
			}
		}
		is MessageContent.Emote -> {
			Text(
				text = buildAnnotatedString {
					append("* ")
					val sender = room.member(senderUserId)
					append(sender?.displayName ?: senderUserId)
					append(" ")
					append(formatting(content.format, content.formattedBody))
				},
				style = MaterialTheme.typography.body1
			)
		}
		is MessageContent.Image -> {
			val image = loadImage(URI(content.url))
			val width = content.info?.width
			val height = content.info?.height
			val specifiedSize = if (width != null && height != null) {
				Modifier.preferredSize(width.toInt().dp, height.toInt().dp)
			} else {
				Modifier
			}
			if (image != null) {
				Image(image, specifiedSize)
			} else {
				Image(Icons.Outlined.BrokenImage, specifiedSize)
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
