package me.dominaezzz.chitchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.github.matrixkt.models.PublicRoomsChunk
import io.github.matrixkt.models.SearchPublicRoomsRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

@Composable
fun PublicRoomsPopup(onDismissRequest: (() -> Unit)? = null) {
	Popup(Alignment.Center, onDismissRequest = onDismissRequest, isFocusable = true) {
		Card(
			modifier = Modifier.fillMaxSize(0.7f),
			elevation = 20.dp
		) {
			PublicRooms()
		}
	}
}

@Composable
fun PublicRooms(modifier: Modifier = Modifier) {
	val client = ClientAmbient.current

	var searchTerm by remember { mutableStateOf("") }
	val shouldPaginate = remember { MutableStateFlow(false) }

	@OptIn(ExperimentalComposeApi::class, ExperimentalCoroutinesApi::class)
	val rooms = remember {
		val batchSize = 40
		snapshotFlow { searchTerm }
			.mapLatest { delay(500); it }
			.map { SearchPublicRoomsRequest.Filter(it) }
			.flatMapLatest { filter ->
				val searchFlow = flow {
					var sinceToken: String? = null
					do {
						val params = SearchPublicRoomsRequest(limit = batchSize, filter = filter, since = sinceToken)
						val response = client.roomApi.queryPublicRooms(params = params)
						emit(response.chunk)
						sinceToken = response.nextBatch
					} while (sinceToken != null)
				}
				searchFlow.runningReduce { acc, value -> acc + value }
					.filterIsInstance<List<PublicRoomsChunk>?>()
					.onStart { emit(null) }
					.transform { chunk ->
						emit(chunk)
						shouldPaginate.first { it }
					}
			}
	}.collectAsState(null).value

	Column(modifier.padding(32.dp)) {
		Text(
			"Explore Rooms",
			Modifier.padding(vertical = 8.dp),
			style = MaterialTheme.typography.h5
		)
		TextField(
			value = searchTerm,
			onValueChange = { searchTerm = it },
			modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
			placeholder = { Text("Find a room… (e.g. #example:matrix.org)") },
			leadingIcon = { Icon(Icons.Default.Search, null) },
			trailingIcon = {
				IconButton({ searchTerm = "" }, enabled = searchTerm.isNotEmpty()) {
					Icon(Icons.Default.Clear, null)
				}
			}
		)

		if (rooms == null) {
			Box(Modifier.fillMaxSize(), Alignment.Center) {
				CircularProgressIndicator()
				DisposableEffect(Unit) {
					shouldPaginate.value = true
					onDispose {
						shouldPaginate.value = false
					}
				}
			}
		} else if (rooms.isEmpty()) {
			Box(Modifier.fillMaxSize(), Alignment.Center) {
				Text("No rooms were found", style = MaterialTheme.typography.h3)
			}
		} else {
			Box(Modifier.fillMaxWidth(), Alignment.TopStart) {
				val state = rememberLazyListState()

				LazyColumn(Modifier.padding(end = 12.dp), state = state) {
					itemsIndexed(rooms) { idx, room ->
						if (idx == rooms.lastIndex) {
							DisposableEffect(room.roomId) {
								shouldPaginate.value = true
								onDispose {
									shouldPaginate.value = false
								}
							}
						}

						val image = room.avatarUrl?.let { loadIcon(URI(it)) }
						@OptIn(ExperimentalMaterialApi::class)
						ListItem(
							icon = {
								if (image != null) {
									Image(
										image,
										null,
										Modifier.preferredSize(40.dp).clip(CircleShape),
										contentScale = ContentScale.Crop
									)
								}
							},
							text = { Text(room.name ?: room.canonicalAlias ?: room.roomId) },
							secondaryText = room.topic?.let { { Text(it) } },
							singleLineSecondaryText = false,
							overlineText = {
								val alias = room.canonicalAlias
								if (alias != null) {
									Text(alias)
								}
							},
							trailing = {
								Row(verticalAlignment = Alignment.CenterVertically) {
									Icon(Icons.Filled.Contacts, null)
									Text(room.numJoinedMembers.toString())
								}
							}
						)
						Divider()
					}
				}

				VerticalScrollbar(
					@OptIn(ExperimentalFoundationApi::class)
					rememberScrollbarAdapter(state, rooms.size, 72.dp),
					Modifier.align(Alignment.CenterEnd).fillMaxHeight()
				)
			}
		}
	}
}
