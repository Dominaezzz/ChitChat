package me.dominaezzz.chitchat

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowSize
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import me.dominaezzz.chitchat.ui.AppView
import me.dominaezzz.chitchat.ui.style.ChitChatTheme
import java.nio.file.Paths

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
	val parser = ArgParser("chitchat")
	val applicationDir by parser.argument(ArgType.String, description = "Application Directory")
	parser.parse(args)

	val applicationDirectory = Paths.get(applicationDir)

	application {
		Window(
			onCloseRequest = { exitApplication() },
			state = rememberWindowState(size = WindowSize(300.dp, 300.dp)),
			title = "Chit Chat"
		) {
			ChitChatTheme {
				AppView(applicationDirectory)
			}
		}
	}
}
