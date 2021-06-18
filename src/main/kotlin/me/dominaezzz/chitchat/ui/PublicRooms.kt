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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matrixkt.api.QueryPublicRooms
import io.github.matrixkt.models.PublicRoomsChunk
import io.github.matrixkt.utils.rpc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import me.dominaezzz.chitchat.util.loadIcon
import java.net.URI

@Composable
fun PublicRooms(modifier: Modifier = Modifier) {
	val client = LocalAppModel.current.client
	val session = LocalAppModel.current.session

	var searchTerm by remember { mutableStateOf("") }
	val shouldPaginate = remember { MutableStateFlow(false) }

	@OptIn(ExperimentalCoroutinesApi::class)
	val rooms = remember {
		val batchSize = 40L
		snapshotFlow { searchTerm }
			.mapLatest { delay(500); it }
			.map { QueryPublicRooms.Filter(it) }
			.flatMapLatest { filter ->
				val searchFlow = flow {
					var sinceToken: String? = null
					do {
						val request = QueryPublicRooms(
							QueryPublicRooms.Url(),
							QueryPublicRooms.Body(limit = batchSize, filter = filter, since = sinceToken)
						)
						val response = client.rpc(request, session.accessToken)
						emit(response.chunk)
						sinceToken = response.nextBatch
					} while (sinceToken != null)
				}
				searchFlow.runningReduce { acc, value -> acc + value }
					.onStart<List<PublicRoomsChunk>?> { emit(null) }
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
					itemsIndexed(rooms, key = { _, room -> room.roomId }) { idx, room ->
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
										Modifier.size(40.dp).clip(CircleShape),
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
					rememberScrollbarAdapter(state),
					Modifier.align(Alignment.CenterEnd).fillMaxHeight()
				)
			}
		}
	}
}
