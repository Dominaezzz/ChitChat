package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matrixkt.events.contents.room.ServerAclContent
import kotlinx.serialization.json.Json

@Stable
class ServerAclEditState(value: ServerAclContent) {
	var allowIpLiterals by mutableStateOf(value.allowIpLiterals)
	val allow = value.allow.toMutableStateList()
	val deny = value.deny.toMutableStateList()

	fun isDirty(original: ServerAclContent): Boolean {
		if (allowIpLiterals != original.allowIpLiterals) return true
		if (allow.toList() != original.allow.toList()) return true
		if (deny.toList() != original.deny.toList()) return true

		return false
	}

	fun create(): ServerAclContent {
		return ServerAclContent(
			allowIpLiterals,
			allow.distinct(),
			deny.distinct()
		)
	}
}

@Composable
fun RoomServerAclEdit(
	current: ServerAclContent,
	state: ServerAclEditState = remember { ServerAclEditState(current) },
	modifier: Modifier = Modifier
) {
	Column(modifier) {
		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Server Access Control List", style = MaterialTheme.typography.h6)
		}

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Checkbox(
				state.allowIpLiterals,
				{ state.allowIpLiterals = it }
			)
			Text(
				"Allow server name that are IP address literals",
				style = MaterialTheme.typography.body1
			)
		}

		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Allowed Servers", style = MaterialTheme.typography.h6)
		}

		// TODO: Display warning if allow list is empty!
		for (index in state.allow.indices) {
			OutlinedTextField(
				value = state.allow[index],
				onValueChange = { state.allow[index] = it },
				maxLines = 1
			)
		}

		CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
			Text("Denied Servers", style = MaterialTheme.typography.h6)
		}

		// TODO: Display warning if deny list contains '*' or our homeserver!
		for (index in state.deny.indices) {
			OutlinedTextField(
				value = state.deny[index],
				onValueChange = { state.deny[index] = it },
				maxLines = 1
			)
		}
	}
}

@Composable
fun RoomServerAclSample() {
	// language=json
	val json = """
	    {
          "allow": [
            "*"
          ],
          "allow_ip_literals": false,
          "deny": [
            "calamari.space",
            "*.lindalap.net",
            "*.matrix.thejewsdid911.com",
            ".matrix.thejewsdid911.com",
            "halogen.city",
            "lindalap.net",
            "matrix.thejewsdid911.com",
            "midov.pl",
            "*.200acres.org",
            "*.glowers.club",
            "*.gossip.love",
            "*.matrix.kiwifarms.net",
            "*.nerdsin.space",
            "*.ordoevangelistarum.com",
            "*.zemos.net",
            "200acres.org",
            "ardaxi.com",
            "c-24-11-108-182.hsd1.ut.comcast.net",
            "glowers.club",
            "gossip.love",
            "matrix.kiwifarms.net",
            "nerdsin.space",
            "ordoevangelistarum.com",
            "zemos.net"
          ]
        }
    """.trimIndent()
	val content = Json.decodeFromString(ServerAclContent.serializer(), json)

	RoomServerAclEdit(content)
}
