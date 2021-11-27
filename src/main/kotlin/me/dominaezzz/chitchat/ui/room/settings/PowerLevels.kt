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
import io.github.matrixkt.models.events.contents.room.PowerLevelsContent
import kotlinx.serialization.json.Json

@Stable
class PowerLevelEditState(value: PowerLevelsContent) {
	var ban by mutableStateOf(value.ban)
	var invite by mutableStateOf(value.invite)
	var kick by mutableStateOf(value.kick)
	var redact by mutableStateOf(value.redact)

	val events = value.events.map { it.toPair() }.toMutableStateMap()
	var eventsDefault by mutableStateOf(value.eventsDefault)
	var stateDefault by mutableStateOf(value.stateDefault)

	val users = value.users.map { it.toPair() }.toMutableStateMap()
	var usersDefault by mutableStateOf(value.usersDefault)

	fun isDirty(original: PowerLevelsContent): Boolean {
		if (ban != original.ban) return true
		if (invite != original.invite) return true
		if (kick != original.kick) return true
		if (redact != original.redact) return true

		if (eventsDefault != original.eventsDefault) return true
		if (stateDefault != original.stateDefault) return true
		if (events.toMap() != original.events.toMap()) return true

		if (usersDefault != original.usersDefault) return true
		if (users.toMap() != original.users.toMap()) return true

		return false
	}

	fun create(): PowerLevelsContent {
		return PowerLevelsContent(
			ban,
			events.filterValues { it != eventsDefault },
			eventsDefault,
			invite,
			kick,
			redact,
			stateDefault,
			users.filterValues { it != usersDefault },
			usersDefault
		)
	}
}

@Composable
fun RoomPowerLevelsEdit(
	current: PowerLevelsContent,
	state: PowerLevelEditState = remember { PowerLevelEditState(current) },
	modifier: Modifier = Modifier
) {
	// TODO: Support smart granular enable/disable based on permissions
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

		for ((userId, powerLevel) in state.users) {
			PermissionEdit(
				value = powerLevel,
				onValueChange = { state.users[userId] = it },
				label = userId
			)
		}

		PermissionEdit(
			value = state.usersDefault,
			onValueChange = { state.usersDefault = it },
			label = "Other users"
		)

		Spacer(Modifier.height(16.dp))

		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Privileged Actions", style = MaterialTheme.typography.h6)
		}

		PermissionEdit(
			value = state.ban,
			onValueChange = { state.ban = it },
			label = "Ban users"
		)

		PermissionEdit(
			value = state.kick,
			onValueChange = { state.kick = it },
			label = "Kick users"
		)

		PermissionEdit(
			value = state.invite,
			onValueChange = { state.invite = it },
			label = "Invite users"
		)

		PermissionEdit(
			value = state.redact,
			onValueChange = { state.redact = it },
			label = "Redact messages"
		)

		// TODO: Make this tidier and more user friendly.
		for ((eventType, powerLevel) in state.events) {
			PermissionEdit(
				value = powerLevel,
				onValueChange = { state.events[eventType] = it },
				label = eventType
			)
		}

		PermissionEdit(
			value = state.eventsDefault,
			onValueChange = { state.eventsDefault = it },
			label = "Send exotic events"
		)

		PermissionEdit(
			value = state.stateDefault,
			onValueChange = { state.stateDefault = it },
			label = "Configure exotic room settings"
		)
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
	label: String
) {
	var showDropDown by remember { mutableStateOf(false) }

	Box {
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


@Composable
fun RoomPowerLevelsSample() {
	// language=json
	val json = """
		{
			"ban": 50,
			"events": {
			  "im.vector.modular.widgets": 50,
			  "m.room.avatar": 50,
			  "m.room.canonical_alias": 50,
			  "m.room.history_visibility": 100,
			  "m.room.name": 50,
			  "m.room.power_levels": 100,
			  "m.room.topic": 50
			},
			"events_default": 0,
			"invite": 0,
			"kick": 50,
			"redact": 50,
			"state_default": 50,
			"users": {
			  "@Mjark:matrix.org": 100,
			  "@abuse:matrix.org": 50,
			  "@matthew:matrix.org": 100,
			  "@richvdh:matrix.org": 100,
			  "@richvdh:sw1v.org": 100,
			  "@travis:t2l.io": 50
			},
			"users_default": 0
		}
		""".trimIndent()
	val content = Json.decodeFromString(PowerLevelsContent.serializer(), json)

	RoomPowerLevelsEdit(content)
}
