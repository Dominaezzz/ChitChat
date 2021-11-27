package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matrixkt.models.events.contents.room.HistoryVisibility
import io.github.matrixkt.models.events.contents.room.HistoryVisibilityContent
import kotlinx.serialization.json.Json

@Stable
class HistoryVisibilityEditState(value: HistoryVisibilityContent) {
	var historyVisibility: HistoryVisibility by mutableStateOf(value.historyVisibility)

	fun isDirty(original: HistoryVisibilityContent): Boolean {
		return historyVisibility != original.historyVisibility
	}

	fun create(): HistoryVisibilityContent {
		return HistoryVisibilityContent(historyVisibility)
	}
}

@Composable
fun RoomHistoryVisibilityEdit(
	value: HistoryVisibilityContent,
	state: HistoryVisibilityEditState = remember { HistoryVisibilityEditState(value) },
	modifier: Modifier = Modifier
) {
	@Composable
	fun option(value: HistoryVisibility, text: String) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			RadioButton(
				selected = state.historyVisibility == value,
				onClick = { state.historyVisibility = value }
			)
			Text(
				text,
				style = MaterialTheme.typography.body1
			)
		}
	}

	Column(modifier) {
		Text("History Visibility", style = MaterialTheme.typography.h6)

		Spacer(Modifier.height(12.dp))

		option(HistoryVisibility.WORLD_READABLE, "Anyone")
		option(HistoryVisibility.SHARED, "Members only (since the point in time of selecting this option)")
		option(HistoryVisibility.INVITED, "Members only (since they were invited)")
		option(HistoryVisibility.JOINED, "Members only (since they joined)")
	}
}


@Composable
fun RoomHistoryVisibilitySample() {
	// language=json
	val json = """
		{
			"history_visibility": "shared"
		}
	""".trimIndent()
	val content = Json.decodeFromString(HistoryVisibilityContent.serializer(), json)

	RoomHistoryVisibilityEdit(content)
}
