package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.github.matrixkt.models.events.contents.room.EncryptionContent
import kotlinx.serialization.json.Json

@Stable
class EncryptionEditState(value: EncryptionContent) {
	var algorithm: String by mutableStateOf(value.algorithm)
	var rotationPeriodMs: Long? by mutableStateOf(value.rotationPeriodMs)
	var rotationPeriodMsgs: Long? by mutableStateOf(value.rotationPeriodMsgs)

	fun isDirty(original: EncryptionContent): Boolean {
		if (algorithm != original.algorithm) return true
		if (rotationPeriodMs != original.rotationPeriodMs) return true
		if (rotationPeriodMsgs != original.rotationPeriodMsgs) return true
		return false
	}

	fun create(): EncryptionContent {
		return EncryptionContent(
			algorithm,
			rotationPeriodMs,
			rotationPeriodMsgs
		)
	}
}

@Composable
fun RoomEncryptionEdit(
	current: EncryptionContent,
	state: EncryptionEditState = remember { EncryptionEditState(current) },
	modifier: Modifier = Modifier
) {
	Column(modifier, Arrangement.SpaceBetween) {
		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Encryption Settings", style = MaterialTheme.typography.h6)
		}

		OutlinedTextField(
			value = state.rotationPeriodMs.toString(),
			onValueChange = { state.rotationPeriodMs = it.toLongOrNull() },
			label = { Text("Rotation Period (Milliseconds)") },
			keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
		)

		OutlinedTextField(
			value = state.rotationPeriodMsgs.toString(),
			onValueChange = { state.rotationPeriodMsgs = it.toLongOrNull() },
			label = { Text("Rotation Period (Number of messages)") },
			keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
		)
	}
}


@Composable
fun RoomEncryptionSample() {
	// language=json
	val json = """
		{
			"algorithm": "m.megolm.v1.aes-sha2"
		}
		""".trimIndent()
	val content = Json.decodeFromString(EncryptionContent.serializer(), json)

	RoomEncryptionEdit(content)
}
