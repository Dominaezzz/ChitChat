package me.dominaezzz.chitchat

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.github.matrixkt.models.PublicRoomsChunk
import io.github.matrixkt.models.SearchPublicRoomsRequest
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.util.IconCache
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

@Composable
fun PublicRoomsPopup(onDismissRequest: (() -> Unit)? = null) {
	val client = ClientAmbient.current
	val contentRepo = ContentRepoAmbient.current

	Popup(Alignment.Center, onDismissRequest = onDismissRequest, isFocusable = true) {
		Providers(ClientAmbient provides client, ContentRepoAmbient provides contentRepo) {
			IconCache {
				Card(
					modifier = Modifier.fillMaxSize(0.7f),
					elevation = 20.dp
				) {
					PublicRooms()
				}
			}
		}
	}
}

@Composable
fun PublicRooms(modifier: Modifier = Modifier) {
	val client = ClientAmbient.current

	var searchTerm by remember { mutableStateOf("") }
	var isLoadingFirstBatch by remember { mutableStateOf(false) }
	val rooms = remember { mutableStateListOf<PublicRoomsChunk>() }
	val shouldPaginate = remember { MutableStateFlow(false) }

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
			leadingIcon = {
				Icon(Icons.Default.Search)
			},
			trailingIcon = {
				IconButton({ searchTerm = "" }, enabled = searchTerm.isNotEmpty()) {
					Icon(Icons.Default.Clear)
				}
			}
		)

		if (isLoadingFirstBatch) {
			Box(Modifier.fillMaxSize(), Alignment.Center) {
				CircularProgressIndicator()
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
							onActive {
								shouldPaginate.value = true
								onDispose {
									shouldPaginate.value = false
								}
							}
						}

						val image = room.avatarUrl?.let { loadIcon(URI(it)) }
						ListItem(
							icon = image?.let {
								{
									Image(it, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
								}
							},
							text = { Text(room.name ?: room.canonicalAlias ?: room.roomId) },
							secondaryText = room.topic?.let { { Text(it) } },
							singleLineSecondaryText = false,
							trailing = {
								Row(verticalAlignment = Alignment.CenterVertically) {
									Icon(Icons.Filled.Contacts)
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

	LaunchedEffect(searchTerm) {
		rooms.clear()
		isLoadingFirstBatch = true

		val batchSize = 40
		var sinceToken: String? = null
		val filter = SearchPublicRoomsRequest.Filter(searchTerm)

		flow {
			// Initial load
			emit(Unit)
			// Subsequent loads
			emitAll(shouldPaginate.filter { it }.conflate().takeWhile { sinceToken != null }.map { })
		}.collect {
			val params = SearchPublicRoomsRequest(limit = batchSize, filter = filter, since = sinceToken)
			val response = client.roomApi.queryPublicRooms(params = params)
			rooms.addAll(response.chunk)
			isLoadingFirstBatch = false // Only needs to run once but this is good enough
			sinceToken = response.nextBatch
		}
	}
}
