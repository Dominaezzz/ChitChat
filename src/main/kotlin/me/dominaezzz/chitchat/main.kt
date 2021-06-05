package me.dominaezzz.chitchat

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowSize
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.dominaezzz.chitchat.ui.AppView
import me.dominaezzz.chitchat.ui.style.ChitChatTheme

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
	application {
		val windowState = rememberWindowState(size = WindowSize(300.dp, 300.dp))
		Window(windowState, title = "Chit Chat") {
			ChitChatTheme {
				AppView()
			}
		}
	}
}
