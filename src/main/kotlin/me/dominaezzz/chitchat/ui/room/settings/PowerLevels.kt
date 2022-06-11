package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.matrixkt.events.contents.room.PowerLevelsContent

class PowerLevelsModel {
	var ban: Long? by mutableStateOf(null)
	var invite: Long? by mutableStateOf(null)
	var kick: Long? by mutableStateOf(null)
	var redact: Long? by mutableStateOf(null)
	var eventsDefault: Long? by mutableStateOf(null)
	var stateDefault: Long? by mutableStateOf(null)
	var usersDefault: Long? by mutableStateOf(null)

	val events = mutableStateMapOf<String, Long>()
	val users = mutableStateMapOf<String, Long>()
}

@Composable
fun RoomPowerLevelsEdit(
	settingsModel: RoomSettingsModel,
	model: PowerLevelsModel,
	current: PowerLevelsContent?,
	modifier: Modifier = Modifier
) {
	// TODO: Show warnings when demoting users or promoting actions

	Column(
		modifier
			.padding(horizontal = 20.dp)
			.verticalScroll(rememberScrollState()),
		Arrangement.SpaceBetween
	) {
		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Privileged Users", style = MaterialTheme.typography.h6)
		}

		for ((userId, powerLevel) in current?.users.orEmpty()) {
			val member by remember(settingsModel, userId) { settingsModel.getMember(userId) }.collectAsState(null)

			PermissionEdit(
				value = model.users[userId] ?: powerLevel,
				onValueChange = { model.users[userId] = it },
				label = member?.displayName ?: userId
			)
		}
		for ((userId, powerLevel) in model.users) {
			if (userId in current?.users.orEmpty()) continue

			val member by remember(settingsModel, userId) { settingsModel.getMember(userId) }.collectAsState(null)

			PermissionEdit(
				value = powerLevel,
				onValueChange = { model.users[userId] = it },
				label = member?.displayName ?: userId
			)
		}

		// TODO: Allow adding new users.

		PermissionEdit(
			value = model.usersDefault ?: current?.usersDefault ?: 0,
			onValueChange = { model.usersDefault = it },
			label = "Other users"
		)

		Spacer(Modifier.height(16.dp))

		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Privileged Actions", style = MaterialTheme.typography.h6)
		}

		PermissionEdit(
			value = model.ban ?: current?.ban ?: 50,
			onValueChange = { model.ban = it },
			label = "Ban users"
		)

		PermissionEdit(
			value = model.kick ?: current?.kick ?: 50,
			onValueChange = { model.kick = it },
			label = "Kick users"
		)

		PermissionEdit(
			value = model.invite ?: current?.invite ?: 50,
			onValueChange = { model.invite = it },
			label = "Invite users"
		)

		PermissionEdit(
			value = model.redact ?: current?.redact ?: 50,
			onValueChange = { model.redact = it },
			label = "Redact messages"
		)

		PermissionEdit(
			value = model.eventsDefault ?: current?.eventsDefault ?: 0,
			onValueChange = { model.eventsDefault = it },
			label = "Default level for message events"
		)

		PermissionEdit(
			value = model.stateDefault ?: current?.stateDefault ?: 0,
			onValueChange = { model.stateDefault = it },
			label = "Default level for state events"
		)

		val eventMap = remember { eventTypes.associateBy({ it.type }, { it.name }) }

		for ((eventType, powerLevel) in current?.events.orEmpty()) {
			PermissionEdit(
				value = model.events[eventType] ?: powerLevel,
				onValueChange = { model.events[eventType] = it },
				label = eventMap[eventType] ?: eventType
			)
		}
		for ((eventType, powerLevel) in model.events) {
			if (eventType in current?.events.orEmpty()) continue

			PermissionEdit(
				value = powerLevel,
				onValueChange = { model.events[eventType] = it },
				label = eventMap[eventType] ?: eventType
			)
		}

		// TODO: Allow adding new event types.
	}
}


// @Serializable
data class PowerLevelName(val name: String, val powerLevel: Long)

private val powerLevelNames = listOf(
	PowerLevelName("Admin", 100),
	PowerLevelName("Moderator", 50),
	PowerLevelName("User", 0)
)

object PowerLevelVisualTransformation : VisualTransformation {
	override fun filter(text: AnnotatedString): TransformedText {
		val powerLevel = text.text.toLongOrNull()
		if (powerLevel != null) {
			// Optimize this once this list is configurable
			val powerLevelName = powerLevelNames.find { it.powerLevel == powerLevel }
			if (powerLevelName != null) {
				val (name, _) = powerLevelName
				return TransformedText(
					AnnotatedString("$powerLevel ($name)"),
					OffsetMapping.Identity
				)
			}
		}
		return TransformedText(text, OffsetMapping.Identity)
	}
}

@Composable
private fun PermissionEdit(
	value: Long,
	onValueChange: (Long) -> Unit,
	label: String,
	modifier: Modifier = Modifier
) {
	var showDropDown by remember { mutableStateOf(false) }

	Box(modifier) {
		var popupWidth by remember { mutableStateOf(0) }

		OutlinedTextField(
			value = value.toString(),
			onValueChange = { onValueChange(it.toLong()) },
			modifier = Modifier.onSizeChanged { popupWidth = it.width },
			label = { Text(label) },
			trailingIcon = {
				IconButton(onClick = { showDropDown = true }) {
					Icon(Icons.Filled.ArrowDropDown, null)
				}
			},
			visualTransformation = PowerLevelVisualTransformation,
			keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
		)

		DropdownMenu(
			expanded = showDropDown,
			onDismissRequest = { showDropDown = false },
			modifier = Modifier.width(with(LocalDensity.current) { popupWidth.toDp() })
		) {
			for ((name, level) in powerLevelNames) {
				DropdownMenuItem(
					onClick = {
						onValueChange(level)
						showDropDown = false
					}
				) {
					Text("$level ($name)")
				}
			}
		}
	}
}

data class EventType(val type: String, val name: String)

// These should be ordered by popularity.
private val eventTypes = listOf(
	EventType("m.room.name", "Set room name"),
	EventType("m.room.topic", "Set room topic"),
	EventType("m.room.avatar", "Set room avatar"),
	EventType("m.room.canonical_alias", "Set room aliases"),
	EventType("m.room.message", "Send messages"),
	EventType("m.room.encrypted", "Send encrypted messages"),
	EventType("m.room.encryption", "Enable encryption"),
	EventType("m.room.guest_access", "Configure guest access"),
	EventType("m.room.history_visibility", "Configure who can see previous messages"),
	EventType("m.room.join_rules", "Configure who can join the room"),
	EventType("m.room.pinned_events", "Configure who can pin/unpin messages"),
	EventType("m.room.power_levels", "Configure permissions in the room"),
	EventType("m.room.server_acl", "Change server access-control list"),
	EventType("m.room.tombstone", "Upgrade the room"),
	// EventType("m.call.answer", ""),
	// EventType("m.call.candidates", ""),
	// EventType("m.call.hangup", ""),
	// EventType("m.call.invite", ""),
	// EventType("m.policy.rule.room", ""),
	// EventType("m.policy.rule.server", ""),
	// EventType("m.policy.rule.user", ""),
	// EventType("m.sticker", ""),
	// EventType("m.key.verification.accept", ""),
	// EventType("m.key.verification.cancel", ""),
	// EventType("m.key.verification.done", ""),
	// EventType("m.key.verification.key", ""),
	// EventType("m.key.verification.mac", ""),
	// EventType("m.key.verification.ready", ""),
	// EventType("m.key.verification.request", ""),
	// EventType("m.key.verification.start", ""),
)
