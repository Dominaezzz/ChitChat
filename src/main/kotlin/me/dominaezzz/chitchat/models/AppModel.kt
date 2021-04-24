package me.dominaezzz.chitchat.models

import io.github.matrixkt.models.Presence
import io.github.matrixkt.utils.MatrixConfig
import io.github.matrixkt.utils.MatrixJson
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.core.LoginSession
import me.dominaezzz.chitchat.sdk.core.*
import me.dominaezzz.chitchat.sdk.crypto.*
import me.dominaezzz.chitchat.sdk.util.getValue
import me.dominaezzz.chitchat.sdk.util.setSerializable
import me.dominaezzz.chitchat.sdk.util.transaction
import me.dominaezzz.chitchat.sdk.util.usingConnection
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

class AppModel(applicationDir: Path) {
	private val scope = CoroutineScope(SupervisorJob())

	val session = usingConnection { conn ->
		LoginSession(
			accessToken = conn.getValue("ACCESS_TOKEN")!!,
			userId = conn.getValue("USER_ID")!!,
			deviceId = conn.getValue("DEVICE_ID")!!,
			discoveryInfo = MatrixJson.decodeFromString(conn.getValue("WELL_KNOWN")!!)
		)
	}

	private val engine = Apache.create {
		connectTimeout = 0
		socketTimeout = 0
	}

	val homeServerUrl = Url(session.discoveryInfo.homeServer.baseUrl)
	val client = HttpClient(engine) { MatrixConfig(homeServerUrl) }

	val contentRepository = ContentRepository(client, applicationDir.resolve("media"))

	private val syncStore = SQLiteSyncStore(applicationDir.resolve("sync.db"))
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

	suspend fun sync() {
		syncClient.sync(timeout = 100000, setPresence = Presence.OFFLINE)
	}
}
