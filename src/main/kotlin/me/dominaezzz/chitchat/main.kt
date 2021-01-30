package me.dominaezzz.chitchat

import androidx.compose.desktop.Window
import androidx.compose.ui.unit.IntSize
import me.dominaezzz.chitchat.ui.AppView
import me.dominaezzz.chitchat.ui.style.ChitChatTheme

fun main() {
	Window(title = "Chit Chat", size = IntSize(300, 300)) {
		ChitChatTheme {
			AppView()
		}
	}
}
