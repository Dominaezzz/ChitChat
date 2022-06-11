package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.matrixkt.events.contents.room.GuestAccess
import io.github.matrixkt.events.contents.room.JoinRule
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun RoomSettings(model: RoomSettingsModel) {
	var selected by remember { mutableStateOf(Setting.General) }

	Row(Modifier.padding(16.dp)) {
		Column(Modifier.width(200.dp)) {
			for (setting in Setting.values()) {
				ListItem(
					text = { Text(setting.name) },
					modifier = Modifier
						.selectableGroup()
						.selectable(
							selected = selected == setting,
							onClick = { selected = setting },
							role = Role.Tab
						)
				)
			}
		}

		Spacer(Modifier.width(8.dp))

		when (selected) {
			Setting.General -> GeneralSettings(model)
			Setting.Permissions -> {
				val currentPowerLevels by model.powerLevelContent.collectAsState()
				RoomPowerLevelsEdit(model, model.powerLevelsModel, currentPowerLevels)
			}
			Setting.Security -> {
				Column {
					RoomHistoryVisibilitySample()
				}
			}
		}
	}
}

private enum class Setting {
	General,
	Permissions,
	Security
}

@Composable
fun RoomSettingsModel.isReadOnly(type: String): Boolean {
	return !remember(this, type) { canUpdateState(type) }
		.collectAsState(false)
		.value
}

@Composable
private fun GeneralSettings(model: RoomSettingsModel) {
	Column {
		val currentName by model.nameContent.collectAsState()
		OutlinedTextField(
			value = model.name ?: currentName?.name ?: "",
			onValueChange = { model.name = it },
			label = { Text("Room Name") },
			maxLines = 1,
			readOnly = model.isReadOnly("m.room.name")
		)

		val currentTopic by model.topicContent.collectAsState()
		OutlinedTextField(
			value = model.topic ?: currentTopic?.topic ?: "",
			onValueChange = { model.topic = it },
			label = { Text("Room Topic") },
			maxLines = 10,
			readOnly = model.isReadOnly("m.room.topic")
		)

		CanonicalAliasEdit(model)

		JoinRuleEdit(model)

		GuestAccessEdit(model)
	}
}

@Composable
private fun CanonicalAliasEdit(model: RoomSettingsModel, modifier: Modifier = Modifier) {
	val currentAlias by model.canonicalAliasContent.collectAsState()
	val readOnly = model.isReadOnly("m.room.canonical_alias")

	Column(modifier) {
		// Consider making this more user-friendly.
		OutlinedTextField(
			value = model.canonicalAlias ?: currentAlias?.alias ?: "",
			onValueChange = { model.canonicalAlias = it },
			label = { Text("Room Canonical Alias") },
			maxLines = 1,
			readOnly = readOnly,
			trailingIcon = {
				val alias = model.canonicalAlias
				if (alias.isNullOrBlank()) return@OutlinedTextField
				val aliasPointsToRoom by model.aliasPointsToRoom.collectAsState()
				when (aliasPointsToRoom) {
					true -> {}
					false -> {}
					null -> {}
				}
			}
		)

		// TODO: Allow adding aliases to the list.
		LazyColumn {
			itemsIndexed(
				model.alternativeAliases ?: currentAlias?.altAliases ?: emptyList(),
				key = { _, it -> it }
			) { index, alias ->
				OutlinedTextField(
					value = alias,
					onValueChange = {
						val list = model.alternativeAliases ?: currentAlias?.altAliases?.toPersistentList()
						if (list != null) {
							model.alternativeAliases = list.set(index, it)
						}
						// else there was some race condition.
					},
					label = { Text("Alternative Alias #${index + 1}") },
					maxLines = 1,
					readOnly = readOnly
				)
			}
		}
	}
}

@Composable
private fun JoinRuleEdit(model: RoomSettingsModel, modifier: Modifier = Modifier) {
	val currentJoinRule by model.joinRulesContent.collectAsState()

	Column(modifier) {
		Text("Join Rules", style = MaterialTheme.typography.h6)

		Spacer(Modifier.height(12.dp))

		@Composable
		fun option(value: JoinRule, text: String) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				RadioButton(
					selected = (model.joinRule ?: currentJoinRule?.joinRule) == value,
					onClick = { model.joinRule = value },
					enabled = !model.isReadOnly("m.room.join_rules")
				)
				Text(
					text,
					style = MaterialTheme.typography.body1
				)
			}
		}

		option(JoinRule.PUBLIC, "Anyone who knows the room's link")
		option(JoinRule.INVITE, "Only people who have been invited")
	}
}

@Composable
private fun GuestAccessEdit(model: RoomSettingsModel, modifier: Modifier = Modifier) {
	val currentGuestAccess by model.guestAccessContent.collectAsState()

	@Composable
	fun option(value: GuestAccess, text: String) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			RadioButton(
				selected = (model.guestAccess ?: currentGuestAccess?.guestAccess) == value,
				onClick = { model.guestAccess = value },
				enabled = !model.isReadOnly("m.room.guest_access")
			)
			Text(
				text,
				style = MaterialTheme.typography.body1
			)
		}
	}

	Column(modifier) {
		Text("Guest Access", style = MaterialTheme.typography.h6)

		Spacer(Modifier.height(12.dp))

		option(GuestAccess.CAN_JOIN, "Guest can join this room")
		option(GuestAccess.FORBIDDEN, "Guests are forbidden from joining this room")
	}
}
