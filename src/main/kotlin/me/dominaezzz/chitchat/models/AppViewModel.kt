package me.dominaezzz.chitchat.models

import io.github.matrixkt.MatrixClient
import io.github.matrixkt.models.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.db.setSerializable
import me.dominaezzz.chitchat.db.transaction
import me.dominaezzz.chitchat.db.usingConnection
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.sdk.crypto.CryptoManager
import me.dominaezzz.chitchat.sdk.crypto.DeviceManager
import me.dominaezzz.chitchat.sdk.crypto.SQLiteCryptoStore
import me.dominaezzz.chitchat.sdk.crypto.SQLiteDeviceStore
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

class AppViewModel(
	private val client: MatrixClient,
	private val session: LoginSession,
	private val applicationDir: Path
) {
	private val scope = CoroutineScope(SupervisorJob())
	private val appDbSemaphore = Semaphore(1)

	private val syncStore = SQLiteSyncStore(applicationDir.resolve("sync.db"))
	val syncClient: SyncClient = SyncClientImpl(scope, session, client, syncStore)

	private val deviceStore = SQLiteDeviceStore(applicationDir.resolve("devices.db"))
	private val deviceManager = DeviceManager(scope, client, syncClient, deviceStore)

	private val random = SecureRandom().asKotlinRandom()
	private val cryptoStore = SQLiteCryptoStore(appDbSemaphore, random)
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
			.filter { it.events.isNotEmpty() }
			.onEach { toDevice ->
				appDbSemaphore.withPermit {
					usingConnection { conn ->
						conn.transaction {
							val sql = "INSERT INTO device_events(type, content, sender) VALUES (?, ?, ?);"
							conn.prepareStatement(sql).use { stmt ->
								for (event in toDevice.events) {
									stmt.setString(1, event.type)
									stmt.setSerializable(2, JsonObject.serializer(), event.content)
									stmt.setString(3, event.sender)
									stmt.executeUpdate()
								}
							}
						}
					}
				}
			}
			.launchIn(scope)

		syncClient.syncFlow
			.mapNotNull { it.toDevice }
			.transform { it.events.forEach { event -> emit(event) } }
			.onEach { event ->
				if (event.type == "m.room.encrypted") {
					cryptoManager.receiveEncryptedDeviceEvent(event, deviceManager)
				}
			}
			.launchIn(scope)
	}

	suspend fun sync() {
		syncClient.sync(timeout = 100000, setPresence = Presence.OFFLINE)
	}
}
