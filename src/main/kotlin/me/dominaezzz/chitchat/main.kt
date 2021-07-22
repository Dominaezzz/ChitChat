package me.dominaezzz.chitchat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowSize
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.runBlocking
import me.dominaezzz.chitchat.models.AppDatabase
import me.dominaezzz.chitchat.models.AppModel
import me.dominaezzz.chitchat.ui.AppView
import me.dominaezzz.chitchat.ui.WelcomeScreen
import me.dominaezzz.chitchat.ui.style.ChitChatTheme
import java.nio.file.Files
import java.nio.file.Paths

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
	val parser = ArgParser("chitchat")
	val applicationDir by parser.argument(ArgType.String, description = "Application Directory")
	parser.parse(args)

	val applicationDirectory = Paths.get(applicationDir)
	Files.createDirectories(applicationDirectory)

	val appDb = AppDatabase(applicationDirectory.resolve("app.db"))
	val isLoggedIn = runBlocking { appDb.getValue("ACCESS_TOKEN") != null }
	var appModel by mutableStateOf<AppModel?>(null)
	if (isLoggedIn) {
		appModel = AppModel(applicationDirectory, appDb)
	}

	try {
		application {
			Window(
				onCloseRequest = { exitApplication() },
				state = rememberWindowState(size = WindowSize(300.dp, 300.dp)),
				title = "Chit Chat"
			) {
				ChitChatTheme {
					if (appModel != null) {
						AppView(appModel!!)
					} else {
						WelcomeScreen(
							{ appModel = AppModel(applicationDirectory, appDb) },
							appDb
						)
					}
				}
			}
		}
	} finally {
		appModel?.close()
		appModel = null
	}
}
