package me.dominaezzz.chitchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BoxWithTooltip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matrixkt.api.GetLoginFlows
import io.github.matrixkt.api.GetWellknown
import io.github.matrixkt.api.Login
import io.github.matrixkt.models.UserIdentifier
import io.github.matrixkt.models.wellknown.DiscoveryInformation
import io.github.matrixkt.utils.*
import io.github.matrixkt.utils.resource.href
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import me.dominaezzz.chitchat.models.AppDatabase
import me.dominaezzz.chitchat.util.rememberCloseable

@Composable
fun WelcomeScreen(
	createAppModel: () -> Unit,
	appDatabase: AppDatabase
) {
	Surface(color = MaterialTheme.colors.background) {
		Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
			CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.high) {
				Text(
					"Chit Chat",
					style = MaterialTheme.typography.h1,
					modifier = Modifier.align(Alignment.CenterHorizontally)
				)
			}

			LoginToHomeServer(
				appDatabase,
				{ createAppModel() },
				Modifier.fillMaxWidth().padding(horizontal = 20.dp)
			)
		}
	}
}

private sealed class ServerDiscoveryResult {
	class Success(val baseUrl: Url) : ServerDiscoveryResult()
	class Unsupported(val reason: String) : ServerDiscoveryResult()
	class Failure(val reason: String) : ServerDiscoveryResult()
	object Loading : ServerDiscoveryResult()
}

@Composable
private fun LoginToHomeServer(
	appDatabase: AppDatabase,
	login: () -> Unit,
	modifier: Modifier = Modifier
) {
	var homeserver: String? by rememberSaveable { mutableStateOf(null) }
	var username: String by rememberSaveable { mutableStateOf("") }
	var password: String by rememberSaveable { mutableStateOf("") }
	var showPassword by rememberSaveable { mutableStateOf(false) }

	val usernameInput = remember { FocusRequester() }
	val passwordInput = remember { FocusRequester() }

	var loginJob by remember { mutableStateOf<Job?>(null) }

	LaunchedEffect(Unit) {
		usernameInput.requestFocus()
	}

	val serverName: String = homeserver ?: username.substringAfter(':')

	val engine = rememberCloseable { Java.create() }

	val usernameFinished = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

	val potentialServerName = username.substringAfter(':')
	val discoveryResult by produceState<ServerDiscoveryResult?>(null, potentialServerName) {
		value = null // Reset discovery state

		usernameFinished.first() // Wait for user to finish typing username.

		value = ServerDiscoveryResult.Loading

		val hostUrl = try {
			URLBuilder(host = serverName).build()
		} catch (e: Exception) {
			value = ServerDiscoveryResult.Failure("Failed to parse URL $serverName")
			return@produceState
		}

		val discoveryInfo = try {
			HttpClient(engine) {
				MatrixConfig(hostUrl)
				Logging {
					level = LogLevel.BODY
					logger = Logger.SIMPLE
				}
			}.use { client ->
				// This endpoint doesn't return Content-Type of json sadly.
				val response = client.get<String> { href(GetWellknown.Url(), url) }
				MatrixJson.decodeFromString<DiscoveryInformation>(response)
			}
		} catch (e: Exception) {
			value = ServerDiscoveryResult.Failure("Failed to get well-known info from $hostUrl")
			e.printStackTrace()
			return@produceState
		}

		val baseUrl = Url(discoveryInfo.homeServer.baseUrl)

		try {
			val loginFlows = HttpClient(engine) {
				MatrixConfig(baseUrl)
				Logging {
					level = LogLevel.BODY
					logger = Logger.SIMPLE
				}
			}.use { client ->
				client.rpc(GetLoginFlows(GetLoginFlows.Url()))
			}

			if (loginFlows.flows.none { it.type == "m.login.password" }) {
				value = ServerDiscoveryResult.Unsupported("$baseUrl does not support password login.")
				return@produceState
			}
		} catch (e: Exception) {
			value = ServerDiscoveryResult.Failure("Failed to get login options from $baseUrl.")
			return@produceState
		}

		homeserver = null
		value = ServerDiscoveryResult.Success(baseUrl)
	}

	Column(
		modifier.padding(16.dp),
		Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
		Alignment.CenterHorizontally
	) {
		Text(
			text = "Login to your Homeserver",
			modifier = Modifier
				.align(Alignment.CenterHorizontally)
				.wrapContentSize(Alignment.Center),
			textAlign = TextAlign.Center
		)

		TextField(
			value = username,
			onValueChange = { username = it },
			modifier = Modifier
				.focusOrder(usernameInput) {
					next = passwordInput
					previous = passwordInput
				}
				.onFocusChanged {
					if (!it.isFocused && username.isNotEmpty()) {
						usernameFinished.tryEmit(Unit)
					}
				},
			label = {
				CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
					Text(
						text = "Username",
						style = MaterialTheme.typography.body2
					)
				}
			},
			textStyle = MaterialTheme.typography.body2,
			placeholder = { Text(text = "@me:matrix.org") },
			singleLine = true,
			enabled = loginJob == null,
			trailingIcon = {
				val result = discoveryResult
				if (homeserver == null && result != null) {
					when (result) {
						is ServerDiscoveryResult.Loading -> {
							CircularProgressIndicator()
						}
						is ServerDiscoveryResult.Success -> {
							InfoTooltip("Found valid home server", Icons.Filled.Check)
						}
						is ServerDiscoveryResult.Failure -> {
							InfoTooltip(result.reason, Icons.Filled.Error)
						}
						is ServerDiscoveryResult.Unsupported -> {
							InfoTooltip(result.reason, Icons.Filled.Warning)
						}
					}
				}
			},
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
		)

		TextField(
			value = password,
			onValueChange = { password = it },
			modifier = Modifier
				.focusOrder(passwordInput) {
					next = usernameInput
					previous = usernameInput
				},
			label = {
				CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
					Text(
						text = "Password",
						style = MaterialTheme.typography.body2
					)
				}
			},
			textStyle = MaterialTheme.typography.body2,
			singleLine = true,
			enabled = loginJob == null,
			trailingIcon = {
				if (showPassword) {
					IconButton(onClick = { showPassword = false }) {
						Icon(Icons.Filled.Visibility, null)
					}
				} else {
					IconButton(onClick = { showPassword = true }) {
						Icon(Icons.Filled.VisibilityOff, null)
					}
				}
			},
			visualTransformation = if (showPassword) {
				VisualTransformation.None
			} else {
				PasswordVisualTransformation()
			}
		)

		val homeserverValid by produceState<ServerDiscoveryResult?>(null, homeserver) {
			value = null
			delay(1500)

			value = ServerDiscoveryResult.Loading

			val homeserverUrl = homeserver
			if (homeserverUrl.isNullOrEmpty()) {
				value = null
				return@produceState
			}

			val providedUrl = try {
				Url(homeserverUrl)
			} catch (e: URLParserException) {
				value = ServerDiscoveryResult.Failure("Failed to parse homeserver URL")
				return@produceState
			}

			HttpClient(engine) {
				MatrixConfig(providedUrl)
				Logging {
					level = LogLevel.ALL
					logger = Logger.SIMPLE
				}
			}.use { client ->
				try {
					val response = client.rpc(GetLoginFlows(GetLoginFlows.Url()))
					if (response.flows.none { it.type == "m.login.password" }) {
						value = ServerDiscoveryResult.Unsupported("Homeserver does not support login with password.")
						return@produceState
					}
				} catch (e: Exception) {
					value = ServerDiscoveryResult.Failure("Could not get login options.")
					return@produceState
				}

				value = ServerDiscoveryResult.Success(providedUrl)
			}
		}

		@OptIn(ExperimentalAnimationApi::class)
		AnimatedVisibility(discoveryResult is ServerDiscoveryResult.Failure) {
			OutlinedTextField(
				value = homeserver ?: "",
				onValueChange = { homeserver = it },
				label = {
					CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
						Text(
							text = "Homeserver",
							style = MaterialTheme.typography.body2
						)
					}
				},
				modifier = Modifier,
				textStyle = MaterialTheme.typography.body2,
				placeholder = { Text(text = "https://matrix.org") },
				singleLine = true,
				readOnly = loginJob != null,
				trailingIcon = {
					if (serverName.isNotEmpty()) {
						when (val valid = homeserverValid) {
							is ServerDiscoveryResult.Loading -> {
								CircularProgressIndicator()
							}
							is ServerDiscoveryResult.Failure -> {
								InfoTooltip(valid.reason, Icons.Filled.Error)
							}
							is ServerDiscoveryResult.Unsupported -> {
								InfoTooltip(valid.reason, Icons.Filled.Warning)
							}
							is ServerDiscoveryResult.Success -> {
								Icon(Icons.Filled.Check, null)
							}
						}
					}
				},
				isError = homeserverValid is ServerDiscoveryResult.Failure
			)
		}

		val scope = rememberCoroutineScope()

		val homeserverUrl = when (val result = discoveryResult) {
			is ServerDiscoveryResult.Success -> result.baseUrl
			is ServerDiscoveryResult.Failure -> (homeserverValid as? ServerDiscoveryResult.Success)?.baseUrl
			else -> null
		}

		Button(
			onClick = {
				loginJob = scope.launch {
					val request = Login(
						Login.Url(),
						Login.Body.Password(
							UserIdentifier.Matrix(username),
							password = password,
							initialDeviceDisplayName = "Chit Chat"
						)
					)
					val result = runCatching {
						HttpClient(engine) { MatrixConfig(homeserverUrl!!) }.use { it.rpc(request) }
					}
					println(result)
					result.onSuccess { response ->
						appDatabase.storeLoginResponse(response)
						login()
					}
					result.onFailure { failure ->
						failure.printStackTrace()
					}
					loginJob = null
				}
			},
			modifier = Modifier.animateContentSize(),
			enabled = homeserverUrl != null && loginJob == null
		) {
			if (loginJob == null) {
				Text(text = "Login")
			} else {
				CircularProgressIndicator()
			}
		}
	}
}

@Composable
private fun InfoTooltip(text: String, icon: ImageVector) {
	BoxWithTooltip(
		tooltip = { Text(text) },
		content = {
			Icon(icon, null)
		}
	)
}
