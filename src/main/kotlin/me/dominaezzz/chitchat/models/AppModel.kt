package me.dominaezzz.chitchat.models

import io.github.matrixkt.api.JoinRoomById
import io.github.matrixkt.api.SetTyping
import io.github.matrixkt.models.MatrixError
import io.github.matrixkt.models.MatrixException
import io.github.matrixkt.models.Presence
import io.github.matrixkt.models.events.contents.room.MemberContent
import io.github.matrixkt.models.events.contents.room.Membership
import io.github.matrixkt.utils.MatrixConfig
import io.github.matrixkt.utils.MatrixJson
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.sdk.crypto.*
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class AppModel(applicationDir: Path, private val appDatabase: AppDatabase) : Closeable {
	private val scope = CoroutineScope(SupervisorJob())

	val session = runBlocking {
		LoginSession(
			accessToken = appDatabase.getValue("ACCESS_TOKEN")!!,
			userId = appDatabase.getValue("USER_ID")!!,
			deviceId = appDatabase.getValue("DEVICE_ID")!!,
			discoveryInfo = MatrixJson.decodeFromString(appDatabase.getValue("WELL_KNOWN")!!)
		)
	}

	val homeServerUrl = Url(session.discoveryInfo.homeServer.baseUrl)
	val client = HttpClient(Java) {
		MatrixConfig(homeServerUrl)

		install(HttpTimeout) {
			connectTimeoutMillis = 10_000
			socketTimeoutMillis = 10000_000 // HttpTimeout.INFINITE_TIMEOUT_MS
			requestTimeoutMillis = 10000_000 //  HttpTimeout.INFINITE_TIMEOUT_MS
		}
	}

	val mediaRepository = MediaRepository(client, applicationDir.resolve("media"))

	val syncStore = SQLiteSyncStore(applicationDir.resolve("sync.db"))
	val syncClient = SyncClient(scope, session, client, syncStore)

	private val deviceCache = DeviceCache(scope, syncClient, client, session, applicationDir.resolve("devices.db"))

	private val random = SecureRandom().asKotlinRandom()
	private val cryptoStore = SQLiteCryptoStore(applicationDir.resolve("crypto.db"), random)
	val cryptoManager = CryptoManager(client, session, cryptoStore, random, deviceCache)

	init {
		syncClient.oneTimeKeysCount
			.mapNotNull { it["signed_curve25519"] }
			.onEach { remaining ->
				if (remaining < 20) {
					println("One time key count ($remaining) is below 20.")
					// cryptoManager.uploadOneTimeKeys(20 - remaining.toInt())
				}
			}
			.launchIn(scope)

		syncClient.syncFlow
			.mapNotNull { it.toDevice }
			.filter { it.events.isNotEmpty() }
			.onEach { toDevice ->
				appDatabase.insertDeviceEvents(toDevice.events)
			}
			.launchIn(scope)

		syncClient.syncFlow
			.mapNotNull { it.toDevice }
			.transform { it.events.forEach { event -> emit(event) } }
			.onEach { event ->
				if (event.type == "m.room.encrypted") {
					cryptoManager.receiveEncryptedDeviceEvent(event)
				}
			}
			.launchIn(scope)
	}

	override fun close() {
		scope.cancel()
		mediaRepository.close()
		client.close()
	}

	suspend fun sync() {
		syncClient.sync(timeout = 100000, setPresence = Presence.OFFLINE)
	}

	fun getMemberList(
		room: Room,
		comparator: Comparator<Pair<String, MemberContent>>
	): Flow<List<Pair<String, MemberContent>>> {
		return flow {
			val stateMap = syncStore.getState(room.id, "m.room.member")

			val members = ArrayList<Pair<String, MemberContent>>(stateMap.size)
			stateMap.mapTo(members) { (stateKey, content) ->
				stateKey to MatrixJson.decodeFromJsonElement(MemberContent.serializer(), content)
			}
			members.removeAll { (_, member) -> member.membership != Membership.JOIN }
			members.sortWith(comparator)

			emit(members.toList())

			room.stateEvents.filter { it.type == "m.room.member" }
				.transform { event ->
					val userId = event.stateKey!!
					val member = MatrixJson.decodeFromJsonElement<MemberContent>(event.content)

					val index = members.indexOfFirst { it.first == userId }

					// If member is already in our list
					if (index != -1) {
						// If member is not in room anymore...
						if (member.membership != Membership.JOIN) {
							// Remove them from the list
							members.removeAt(index)
							emit(members.toList())
						} else {
							val oldItem = members[index]
							// If the member content needs updating.
							if (member != oldItem.second) {
								val newItem = userId to member
								val compareResult = comparator.compare(oldItem, newItem)
								if (compareResult == 0) {
									// If the comparison is equal, then we can simply replace the item.
									members[index] = newItem
									emit(members.toList())
								} else {
									// If the comparison is not equal, then we need to re-insert the item.
									members.removeAt(index)
									val searchResult = members.binarySearch(newItem, comparator)
									val insertionPoint = if (searchResult >= 0) {
										searchResult
									} else {
										-(searchResult + 1)
									}
									members.add(insertionPoint, newItem)
									emit(members.toList())

									// Instead of doing "remove and insert" we could be clever and do a "set and rotate".
								}
							}
						}
					} else {
						// If member has now joined the room.
						if (member.membership == Membership.JOIN) {
							val item = userId to member
							val searchResult = members.binarySearch(item, comparator)
							val insertionPoint = if (searchResult >= 0) {
								searchResult
							} else {
								-(searchResult + 1)
							}
							members.add(insertionPoint, item)
							emit(members.toList())
						}
					}
				}
				.flowOn(Dispatchers.Default)
				.collect { list ->
					emit(list)
				}
		}
	}


	private val _joinsInProgress = MutableStateFlow<Set<String>>(emptySet())
	val joinsInProgress: StateFlow<Set<String>> get() = _joinsInProgress.asStateFlow()

	fun joinRoom(roomId: String) {
		val previousJoins = _joinsInProgress.getAndUpdate { it + roomId }
		if (roomId in previousJoins) {
			println("Already joining room $roomId. Ignoring!")
			return
		}

		scope.launch {
			val checkForJoin = launch(start = CoroutineStart.UNDISPATCHED) {
				syncClient.joinedRooms.first { roomId in it }
			}

			val joinRequest = JoinRoomById(JoinRoomById.Url(roomId), JoinRoomById.Body())
			try {
				val response = client.rpc(joinRequest, session.accessToken)
				println("Joined room (id = ${response.roomId})!")
				checkForJoin.join()
			} catch (e: MatrixException) {
				e.printStackTrace()
			}

			_joinsInProgress.update { it - roomId }
		}
	}

	@ExperimentalTime
	private sealed class TypingUpdateResult {
		object Success : TypingUpdateResult()
		class RateLimit(val duration: Duration) : TypingUpdateResult()
		class Failed(val exception: Exception) : TypingUpdateResult()
	}
	@ExperimentalTime
	private suspend fun updateIsTyping(request: SetTyping): TypingUpdateResult {
		return try {
			client.rpc(request, session.accessToken)
			TypingUpdateResult.Success
		} catch (e: MatrixException) {
			val matrixError = e.error
			if (matrixError is MatrixError.LimitExceeded) {
				val waitPeriod = Duration.milliseconds(matrixError.retryAfterMillis)
				TypingUpdateResult.RateLimit(waitPeriod)
			} else {
				TypingUpdateResult.Failed(e)
			}
		} catch (e: Exception) {
			TypingUpdateResult.Failed(e)
		}
	}

	@OptIn(ExperimentalTime::class, FlowPreview::class, ExperimentalCoroutinesApi::class)
	suspend fun publishTypingNotifications(roomId: String, draft: Flow<String>) {
		val typingUrl = SetTyping.Url(session.userId, roomId)

		coroutineScope {
			val draftChannel = draft.produceIn(this)

			var lastNotification: TimeMark? = null
			var rateLimitMark: TimeMark? = null

			while (isActive) {
				val isStillTyping = select<Boolean> {
					draftChannel.onReceive { it.isNotEmpty() }
					onTimeout(3_000) { false }
				}

				if (rateLimitMark != null && rateLimitMark.hasNotPassedNow()) {
					continue
				}

				if (isStillTyping) {
					if (lastNotification == null || lastNotification.elapsedNow() > Duration.seconds(20)) {
						// Need to notify server again or for the first time.

						val request = SetTyping(typingUrl, SetTyping.Body(typing = true, timeout = 25_000))
						when (val result = updateIsTyping(request)) {
							is TypingUpdateResult.Success -> {
								lastNotification = TimeSource.Monotonic.markNow()
							}
							is TypingUpdateResult.Failed -> {
								result.exception.printStackTrace()
								// Failed to set typing notification, so we'll retry next time.
							}
							is TypingUpdateResult.RateLimit -> {
								// Set rate limit period.
								rateLimitMark = TimeSource.Monotonic.markNow() + result.duration
								// We couldn't set our typing notification, so we'll retry next time.
							}
						}
					}
				} else {
					if (lastNotification != null && lastNotification.elapsedNow() < Duration.seconds(25)) {
						// Need to tell server that we're done typing.

						val request = SetTyping(typingUrl, SetTyping.Body(typing = false))
						when (val result = updateIsTyping(request)) {
							is TypingUpdateResult.Success -> {
								lastNotification = null
							}
							is TypingUpdateResult.Failed -> {
								result.exception.printStackTrace()
								// Failed to remove typing notification, so we'll rely on the timeouts.
							}
							is TypingUpdateResult.RateLimit -> {
								// Set rate limit period.
								rateLimitMark = TimeSource.Monotonic.markNow() + result.duration
								// We couldn't unset our typing notification, so we'll rely on timeout.
							}
						}
					}
				}
			}
		}
	}
}
