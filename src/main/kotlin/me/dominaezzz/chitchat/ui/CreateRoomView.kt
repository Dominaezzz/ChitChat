package me.dominaezzz.chitchat.ui

import androidx.compose.foundation.BoxWithTooltip
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.github.matrixkt.api.SearchUserDirectory
import io.github.matrixkt.models.RoomPreset
import io.github.matrixkt.models.RoomVersionStability
import io.github.matrixkt.models.RoomVisibility
import me.dominaezzz.chitchat.models.CreateRoomModel
import me.dominaezzz.chitchat.util.Banner
import me.dominaezzz.chitchat.util.TooltipContent
import me.dominaezzz.chitchat.util.loadImage
import java.net.URI

@Composable
fun CreateRoomView(
	model: CreateRoomModel,
	onCreateClicked: () -> Unit,
	onCancelClicked: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(modifier.width(560.dp)) {
		val status = model.createStatus.collectAsState().value

		if (status is CreateRoomModel.Status.Failed) {
			var isErrorDismissed by remember { mutableStateOf(false) }
			if (!isErrorDismissed) {
				Banner(
					text = { Text(text = status.render()) },
					confirmButton = {
						TextButton(onClick = { isErrorDismissed = true }) {
							Text("DISMISS")
						}
					},
					modifier = Modifier.fillMaxWidth()
				)
				Divider()
			}
		}

		Column(
			modifier = Modifier.padding(horizontal = 24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			Text(
				text = "Create a room",
				modifier = Modifier.paddingFrom(FirstBaseline, before = 40.dp),
				style = MaterialTheme.typography.h6
			)

			// Pick preset
			Box {
				var isDropDownExpanded by remember { mutableStateOf(false) }

				OutlinedTextField(
					value = when (model.preset) {
						RoomPreset.PUBLIC_CHAT -> "Public Chat"
						RoomPreset.PRIVATE_CHAT -> "Private Chat"
						RoomPreset.TRUSTED_PRIVATE_CHAT -> "Trusted Private Chat"
						null -> "Custom"
					},
					onValueChange = {},
					readOnly = true,
					label = { Text("Preset") },
					trailingIcon = {
						IconButton(onClick = { isDropDownExpanded = true }) {
							Icon(Icons.Default.ExpandMore, contentDescription = null)
						}
					}
				)
				DropdownMenu(
					expanded = isDropDownExpanded,
					onDismissRequest = { isDropDownExpanded = false }
				) {
					DropdownMenuItem(
						onClick = {
							model.preset = RoomPreset.TRUSTED_PRIVATE_CHAT
							isDropDownExpanded = false
						}
					) {
						Text("Trusted Private Chat")
					}
					DropdownMenuItem(
						onClick = {
							model.preset = RoomPreset.PRIVATE_CHAT
							isDropDownExpanded = false
						}
					) {
						Text("Private Chat")
					}
					DropdownMenuItem(
						onClick = {
							model.preset = RoomPreset.PUBLIC_CHAT
							isDropDownExpanded = false
						}
					) {
						Text("Public Chat")
					}
				}
			}

			// Pick room version
			Box {
				val versionsCapability by model.roomVersionCapabilities.collectAsState()
				var isDropDownExpanded by remember { mutableStateOf(false) }

				OutlinedTextField(
					value = run {
						val selectedVersion = model.roomVersion
						if (selectedVersion != null) {
							if (selectedVersion == versionsCapability?.default) {
								"$selectedVersion (Default)"
							} else {
								selectedVersion
							}
						} else {
							versionsCapability?.let { "${it.default} (Default)" } ?: ""
						}
					},
					onValueChange = {},
					enabled = versionsCapability != null,
					readOnly = true,
					label = { Text("Room Version (Optional)") },
					trailingIcon = {
						IconButton(
							onClick = { isDropDownExpanded = true },
							enabled = versionsCapability != null,
							content = {
								Icon(Icons.Default.ExpandMore, contentDescription = null)
							}
						)
					}
				)
				val capability = versionsCapability
				if (capability != null) {
					DropdownMenu(
						expanded = isDropDownExpanded,
						onDismissRequest = { isDropDownExpanded = false }
					) {
						for ((version, stability) in capability.available) {
							DropdownMenuItem(
								onClick = {
									model.roomVersion = version
									isDropDownExpanded = false
								}
							) {
								Text(
									text = buildString {
										append(version)
										if (stability == RoomVersionStability.UNSTABLE) {
											append(" (unstable)")
										}
										if (version == capability.default) {
											append(" (Default)")
										}
									}
								)
							}
						}
					}
				}
			}

			// Visibility in public room directory.
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) {
				Checkbox(
					checked = model.visibility != RoomVisibility.PRIVATE,
					onCheckedChange = {
						model.visibility = if (it) RoomVisibility.PUBLIC else RoomVisibility.PRIVATE
					}
				)
				Text("Publish to public room directory")
			}

			// Enabling encryption
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) {
				BoxWithTooltip(
					tooltip = { TooltipContent("E2EE is not yet supported") },
					content = {
						Checkbox(
							checked = model.encryptionEnabled,
							onCheckedChange = { model.encryptionEnabled = it },
							enabled = false // Encryption not supported yet.
						)
					}
				)

				Text("End-to-end encryption (Cannot be disabled later)")

				if (model.encryptionEnabled && model.preset == RoomPreset.PUBLIC_CHAT) {
					BoxWithTooltip(
						tooltip = { TooltipContent("E2EE is discouraged in public rooms") },
						content = {
							Icon(Icons.Default.WarningAmber, contentDescription = null)
						}
					)
				}
			}

			// Inviting users
			Column {
				var isAutoCompleteVisible by remember { mutableStateOf(false) }
				var autoCompleteWidth by remember { mutableStateOf(0) }

				OutlinedTextField(
					value = model.userSearchTerm,
					onValueChange = {
						model.userSearchTerm = it
						isAutoCompleteVisible = it.isNotEmpty()
					},
					modifier = Modifier.fillMaxWidth()
						.onGloballyPositioned { autoCompleteWidth = it.size.width },
					singleLine = true,
					placeholder = { Text("Invite users...") }
				)

				if (isAutoCompleteVisible) {
					Popup(
						popupPositionProvider = CustomPositionProvider,
						onDismissRequest = { isAutoCompleteVisible = false }
					) {
						Card(
							modifier = Modifier.width(with(LocalDensity.current) { autoCompleteWidth.toDp() }),
							elevation = 8.dp
						) {
							Column {
								val users by model.suggestedUsers.collectAsState()
								for (user in users) {
									InvitableUser(
										user,
										Modifier.clickable {
											model.invitedUsers.add(user)
											model.userSearchTerm = ""
											isAutoCompleteVisible = false
										}
									)
								}
							}
						}
					}
				}
			}
			LazyColumn {
				items(
					model.invitedUsers,
					key = { it.userId }
				) { user ->
					InvitableUser(
						user,
						trailing = {
							IconButton(
								onClick = { model.invitedUsers.removeAll { it.userId == user.userId } },
								content = {
									Icon(Icons.Default.Remove, contentDescription = null)
								}
							)
						}
					)
				}
			}

			TextField(
				model.name,
				onValueChange = { model.name = it },
				modifier = Modifier.fillMaxWidth(),
				label = {
					Text(
						text = if (model.visibility == RoomVisibility.PUBLIC) {
							"Name"
						} else {
							"Name (Optional)"
						}
					)
				},
				singleLine = true
			)
			TextField(
				model.topic,
				onValueChange = { model.topic = it },
				modifier = Modifier.fillMaxWidth(),
				label = { Text("Topic (Optional)") }
			)
			TextField(
				model.roomAliasName,
				onValueChange = { model.roomAliasName = it },
				modifier = Modifier.fillMaxWidth(),
				label = {
					Text(
						text = if (model.visibility == RoomVisibility.PUBLIC) {
							"Canonical Alias"
						} else {
							"Canonical Alias (Optional)"
						}
					)
				},
				isError = model.roomAliasName.any { !isCharLegal(it) },
				visualTransformation = RoomAliasTransformation(model.serverName)
			)
		}

		Spacer(Modifier.height(28.dp))

		Row(
			modifier = Modifier.align(Alignment.End)
				.height(52.dp)
				.padding(8.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			OutlinedButton(
				onClick = { onCancelClicked() },
			) {
				Text("CANCEL")
			}
			Button(
				onClick = { onCreateClicked() },
			) {
				Text("CREATE")
			}
		}
	}
}

@Composable
private fun InvitableUser(
	user: SearchUserDirectory.User,
	modifier: Modifier = Modifier,
	trailing: @Composable (() -> Unit)? = null
) {
	@OptIn(ExperimentalMaterialApi::class)
	ListItem(
		modifier = modifier,
		icon = {
			val displayImage = user.avatarUrl?.let { loadImage(URI(it)) }
			if (displayImage != null) {
				Image(displayImage, contentDescription = null, Modifier.size(48.dp))
			} else {
				Image(
					Icons.Default.Person,
					contentDescription = null,
					Modifier.size(48.dp)
				)
			}
		},
		text = {
			Text(user.displayName ?: user.userId)
		},
		secondaryText = if (user.displayName != null) {
			{
				Text(user.userId)
			}
		} else {
			null
		},
		trailing = trailing
	)
}

private fun isCharLegal(c: Char): Boolean {
	return when (c) {
		in 'a'..'z',
		in '0'..'9',
		'.', '_', '=', '-', '/' -> true
		else -> false
	}
}

private class RoomAliasTransformation(private val serverName: String) : VisualTransformation {
	private val mapping = object : OffsetMapping {
		override fun originalToTransformed(offset: Int): Int {
			return offset + 1
		}

		override fun transformedToOriginal(offset: Int): Int {
			return offset - 1
		}
	}
	private val mutedStyle = SpanStyle(color = Color.Gray)

	override fun filter(text: AnnotatedString): TransformedText {
		if (text.isEmpty()) {
			return TransformedText(text, OffsetMapping.Identity)
		}

		val newText = buildAnnotatedString {
			withStyle(mutedStyle) {
				append('#')
			}
			for (c in text) {
				if (isCharLegal(c)) {
					append(c)
				} else {
					withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
						append(c)
					}
				}
			}
			withStyle(mutedStyle) {
				append(':')
				append(serverName)
			}
		}
		return TransformedText(newText, mapping)
	}
}

private object CustomPositionProvider : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize
	): IntOffset {
		return anchorBounds.bottomLeft
	}
}
