package me.dominaezzz.chitchat

import androidx.compose.desktop.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.dominaezzz.chitchat.ui.ChitChatTheme

fun main() {
	Window(title = "Chit Chat", size = IntSize(300, 300)) {
		ChitChatTheme {
			AppView()
		}
	}
}
