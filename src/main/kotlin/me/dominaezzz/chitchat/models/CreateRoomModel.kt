package me.dominaezzz.chitchat.models

import androidx.compose.runtime.*
import io.github.matrixkt.api.CreateRoom
import io.github.matrixkt.api.GetCapabilities
import io.github.matrixkt.api.SearchUserDirectory
import io.github.matrixkt.models.*
import io.github.matrixkt.models.events.contents.room.EncryptionContent
import io.github.matrixkt.utils.MatrixJson
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.dominaezzz.chitchat.sdk.core.LoginSession

class CreateRoomModel(
	private val scope: CoroutineScope,
	private val httpClient: HttpClient,
	private val session: LoginSession
) {
	val serverName = session.userId.substringAfter(':')

	var preset: RoomPreset? by mutableStateOf(RoomPreset.PRIVATE_CHAT)
	var visibility by mutableStateOf(RoomVisibility.PRIVATE)

	var name: String by mutableStateOf("")
	var topic: String by mutableStateOf("")
	// var isDM: Boolean? by mutableStateOf(null)

	val invitedUsers = mutableStateListOf<SearchUserDirectory.User>()

	var roomAliasName: String by mutableStateOf("")

	var roomVersion: String? by mutableStateOf(null)

	var encryptionEnabled: Boolean by mutableStateOf(false)

	var userSearchTerm: String by mutableStateOf("")
	@OptIn(ExperimentalCoroutinesApi::class)
	val suggestedUsers: StateFlow<List<SearchUserDirectory.User>> = snapshotFlow { userSearchTerm }
		.mapLatest { searchTerm ->
			if (searchTerm.isNotEmpty()) {
				val request = SearchUserDirectory(
					SearchUserDirectory.Url(),
					SearchUserDirectory.Body(
						limit = (4 + invitedUsers.size).toLong(),
						searchTerm = searchTerm
					)
				)
				val response = httpClient.rpc(request, session.accessToken)
				response.results
			} else {
				emptyList()
			}
		}
		.combine(snapshotFlow { invitedUsers.map { it.userId }.toSet() }) { suggested, invited ->
			suggested.filter { it.userId !in invited }
		}
		.stateIn(scope, SharingStarted.Lazily, initialValue = emptyList())

	val roomVersionCapabilities: StateFlow<RoomVersionsCapability?> = flow {
		val request = GetCapabilities(GetCapabilities.Url())
		val response = httpClient.rpc(request, session.accessToken)
		val versionsJson = response.capabilities["m.room_versions"]
		if (versionsJson != null) {
			val roomVersions = MatrixJson.decodeFromJsonElement<RoomVersionsCapability>(versionsJson)
			emit(roomVersions)
		}
	}
		.stateIn(scope, SharingStarted.Lazily, null)

	private val _createStatus = MutableStateFlow<Status?>(null)
	val createStatus: StateFlow<Status?> = _createStatus.asStateFlow()

	sealed class Status {
		object Creating : Status()
		class Created(val roomId: String) : Status()
		class Failed(val exception: Throwable) : Status() {
			fun render(): String {
				exception.message?.let { return it }

				return if (exception is MatrixException) {
					exception.error::class.simpleName
				} else {
					null
				}.orEmpty()
			}
		}
	}

	private suspend fun createRoomInternal() {
		while (true) {
			when (val currentStatus = _createStatus.value) {
				is Status.Creating -> return
				is Status.Created -> return
				is Status.Failed, null -> {
					if (_createStatus.compareAndSet(currentStatus, Status.Creating)) {
						break
					}
				}
			}
		}
		val result = runCatching {
			val request = buildRequest()
			httpClient.rpc(request, session.accessToken)
		}

		val newStatus = result.fold({ response -> Status.Created(response.roomId) }, { Status.Failed(it) })

		val prevStatus = _createStatus.getAndUpdate { newStatus }
		if (prevStatus != Status.Creating) {
			println("Something terrible went wrong while creating the room!")
		}
	}

	fun createRoom() {
		scope.launch {
			createRoomInternal()
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	private fun buildRequest(): CreateRoom {
		return CreateRoom(
			CreateRoom.Url(),
			CreateRoom.Body(
				preset = preset,
				visibility = visibility,
				roomVersion = roomVersion,
				name = name.takeIf { it.isNotBlank() },
				topic = topic.takeIf { it.isNotBlank() },
				roomAliasName = roomAliasName.takeIf { it.isNotBlank() },
				invite = invitedUsers.map { it.userId },
				initialState = buildList {
					if (encryptionEnabled) {
						val content = EncryptionContent(
							algorithm = "m.megolm.v1.aes-sha2"
						)
						val state = CreateRoom.StateEvent(
							type = "m.room.encryption",
							content = MatrixJson.encodeToJsonElement(content).jsonObject
						)
						add(state)
					}
				}
			)
		)
	}
}
