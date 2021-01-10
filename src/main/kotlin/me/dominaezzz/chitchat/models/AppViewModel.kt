package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import me.dominaezzz.chitchat.LoginSession
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.sdk.crypto.CryptoManager
import me.dominaezzz.chitchat.sdk.crypto.SQLiteCryptoStore
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

class AppViewModel(
	private val client: MatrixClient,
	private val dbSemaphore: Semaphore,
	private val session: LoginSession
) {
	private val scope = CoroutineScope(SupervisorJob())
	private val syncStore = SQLiteSyncStore(dbSemaphore)
	val syncClient: SyncClient = SyncClientImpl(scope, session, client, dbSemaphore, syncStore)

	private val random = SecureRandom().asKotlinRandom()
	private val cryptoStore = SQLiteCryptoStore(dbSemaphore, random)
	val cryptoManager = CryptoManager(client, session, cryptoStore, random)

	init {
		syncClient.oneTimeKeysCount
			.mapNotNull { it["signed_curve25519"] }
			.onEach { remaining ->
				if (remaining < 20) {
					cryptoManager.uploadOneTimeKeys(20 - remaining.toInt())
				}
			}
			.launchIn(scope)

		syncClient.syncFlow
			.mapNotNull { it.toDevice }
			.transform { it.events.forEach { event -> emit(event) } }
			.onEach { event ->
				if (event.type == "m.room.encrypted") {
					cryptoManager.receiveEncryptedDeviceEvent(event, syncClient)
				}
			}
			.launchIn(scope)
	}

	suspend fun sync() {
		syncClient.sync(timeout = 100000, setPresence = Presence.OFFLINE)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	fun getRooms(): Flow<List<RoomHeader>> {
		val comparator = compareByDescending<RoomHeader> { it.favourite != null }
			.then(nullsLast(compareBy { it.favourite?.order }))
			.thenBy { it.lowPriority != null }
			.then(nullsLast(compareBy { it.lowPriority?.order }))
			.thenBy { it.displayName }

		return syncClient.joinedRooms
			.flatMapLatest { joinedRooms ->
				val roomHeaders = joinedRooms.values.map { room ->
					combine(
						room.getDisplayName(session.userId),
						room.getDisplayAvatar(session.userId),
						room.joinedMemberCount,
						room.tags
					) { displayName, displayAvatar, memberCount, tags ->
						RoomHeader(
							room.id,
							room,
							displayName,
							memberCount,
							displayAvatar,
							tags["m.favourite"],
							tags["m.lowpriority"]
						)
					}
				}
				combine(roomHeaders) { headers -> headers.sortedWith(comparator) }
			}
	}
}
