package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.clientserver.api.UploadKeys
import io.github.matrixkt.clientserver.models.*
import io.github.matrixkt.client.OlmEventPayload
import io.github.matrixkt.events.contents.ForwardedRoomKeyContent
import io.github.matrixkt.events.contents.RoomKeyContent
import io.github.matrixkt.events.contents.room.EncryptedContent
import io.github.matrixkt.clientserver.models.sync.Event
import io.github.matrixkt.olm.InboundGroupSession
import io.github.matrixkt.olm.Message
import io.github.matrixkt.olm.Session
import io.github.matrixkt.olm.Utility
import io.github.matrixkt.client.MatrixJson
import io.github.matrixkt.client.rpc
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import me.dominaezzz.chitchat.sdk.core.LoginSession
import kotlin.random.Random

class CryptoManager(
	private val client: HttpClient,
	private val loginSession: LoginSession,
	private val store: CryptoStore,
	private val random: Random,
	private val deviceCache: DeviceCache
) {
	suspend fun uploadIdentityKeys() {
		val userId = loginSession.userId
		val deviceId = loginSession.deviceId

		val request = store.usingAccount { account ->
			val idKeys = account.identityKeys
			val deviceKeys = DeviceKeys(
				userId = userId,
				deviceId = deviceId,
				algorithms = listOf("m.olm.v1.curve25519-aes-sha2", "m.megolm.v1.aes-sha2"),
				keys = mapOf(
					"curve25519:$deviceId" to idKeys.curve25519,
					"ed25519:$deviceId" to idKeys.ed25519
				),
				signatures = emptyMap()
			)

			UploadKeys(
				UploadKeys.Url(),
				UploadKeys.Body(
					deviceKeys = account.signObject(
						DeviceKeys.serializer(),
						deviceKeys,
						userId,
						deviceId
					)
				)
			)
		}

		client.rpc(request, loginSession.accessToken)
	}

	suspend fun uploadOneTimeKeys(numberOfKeysToUpload: Int = 10) {
		val userId = loginSession.userId
		val deviceId = loginSession.deviceId

		val request = store.modifyAccount { account ->
			val keysToGenerate = numberOfKeysToUpload - account.oneTimeKeys.curve25519.size
			if (keysToGenerate > 0) {
				account.generateOneTimeKeys(keysToGenerate.toLong(), random)
			}
			val curve = account.oneTimeKeys.curve25519
			UploadKeys(
				UploadKeys.Url(),
				UploadKeys.Body(
					oneTimeKeys = curve.entries.associate { (key, value) ->
						"signed_curve25519:$key" to account.signObject(
							KeyObject.serializer(),
							KeyObject(key=value, signatures = emptyMap()),
							userId, deviceId
						)
					}
				)
			)
		}

		client.rpc(request, loginSession.accessToken)

		// NOTE: There's a small race condition here, making this method not concurrency safe.

		store.modifyAccount { account ->
			account.markOneTimeKeysAsPublished()
		}
	}

	private suspend fun decryptOlmEvent(content: EncryptedContent.OlmV1): String {
		val ourIdentityKey = store.usingAccount {
			// NOTE: Should have this cached outside the account somewhere.
			it.identityKeys.curve25519
		}
		val ciphertextInfo = content.ciphertext[ourIdentityKey]
		requireNotNull(ciphertextInfo) { "Encrypted device message was not meant for us." }
		val encryptedMsg = Message(ciphertextInfo.body!!, ciphertextInfo.type!!.toLong())

		val sessions = store.getInboundSessions(content.senderKey)

		try {
			for (session in sessions) {
				try {
					val decryptedPayload = withContext(Dispatchers.Default) {
						session.decrypt(encryptedMsg)
					}
					// Mark as recently used.
					store.markAsRecentlyUsed(session.sessionId)
					return decryptedPayload
				} catch (e: Exception) {
					// Couldn't decrypt message with this session

					check(session.matchesInboundSessionFrom(content.senderKey, ciphertextInfo.body!!)) {
						"Could not decrypt with matching session."
					}
				}
			}
		} finally {
			sessions.forEach { it.clear() }
		}

		check(encryptedMsg is Message.PreKey) {
			"Could not find existing olm session to decrypt olm message."
		}

		val session = store.usingAccount { account ->
			Session.createInboundSessionFrom(account, content.senderKey, ciphertextInfo.body!!)
		}

		try {
			val decryptedPayload = withContext(Dispatchers.Default) {
				session.decrypt(encryptedMsg)
			}

			// Store only if successfully decrypted.
			store.storeInboundSession(content.senderKey, session)

			return decryptedPayload
		} finally {
			session.clear()
		}
	}

	suspend fun receiveEncryptedDeviceEvent(event: Event) {
		try {
			require(event.type == "m.room.encrypted")
			val content = MatrixJson.decodeFromJsonElement(EncryptedContent.serializer(), event.content)

			// Only support olm for to device events.
			if (content !is EncryptedContent.OlmV1) return

			handleEncryptedDeviceEvent(event.sender!!, content)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	private suspend fun handleEncryptedDeviceEvent(sender: String, content: EncryptedContent.OlmV1) {
		val decryptedContent = decryptOlmEvent(content)
		val olmPayload = MatrixJson.decodeFromString<OlmEventPayload>(decryptedContent)

		check(olmPayload.sender == sender)

		val senderDevices = deviceCache.getUserDevices(olmPayload.sender)
		val senderSigningKey = senderDevices.asSequence()
			.filter { keys -> runCatching { usingUtility { it.verifyEd25519Signature(keys) } }.isSuccess }
			.filter { it.keys["curve25519:${it.deviceId}"] == content.senderKey }
			.filter { "m.olm.v1.curve25519-aes-sha2" in it.algorithms }
			.mapNotNull { it.keys["ed25519:${it.deviceId}"] }
			.distinct()
			.singleOrNull() ?: return

		check(olmPayload.senderKeys.getValue("ed25519") == senderSigningKey)
		check(olmPayload.recipient == loginSession.userId)

		val ourSigningKey = store.usingAccount { it.identityKeys.ed25519 }
		check(olmPayload.recipientKeys.getValue("ed25519") == ourSigningKey)

		when (olmPayload.type) {
			"m.room_key" -> {
				val roomKey = MatrixJson.decodeFromJsonElement(RoomKeyContent.serializer(), olmPayload.content)
				if (roomKey.algorithm != "m.megolm.v1.aes-sha2") return

				val session = InboundGroupSession(roomKey.sessionKey)
				try {
					if (session.sessionId != roomKey.sessionId) {
						// Log this occurred.
						return
					}

					store.storeGroupSession(
						roomKey.roomId,
						content.senderKey,
						session,
						senderSigningKey
					)
					megolmSessionFlow.emit(MegolmId(roomKey.roomId, content.senderKey, roomKey.sessionId))
				} finally {
					session.clear()
				}
			}
			"m.forwarded_room_key" -> {
				val forwardedRoomKey = MatrixJson.decodeFromJsonElement(ForwardedRoomKeyContent.serializer(), olmPayload.content)
				if (forwardedRoomKey.algorithm != "m.megolm.v1.aes-sha2") return

				val session = InboundGroupSession.import(forwardedRoomKey.sessionKey)
				try {
					check(forwardedRoomKey.sessionId == session.sessionId)

					// Should we only store this if we asked for it?

					// store.storeGroupSession(
					// 	forwardedRoomKey.roomId,
					// 	forwardedRoomKey.senderKey,
					// 	session,
					// 	forwardedRoomKey.senderClaimedEd25519Key,
					// 	forwardedRoomKey.forwardingCurve25519KeyChain + content.senderKey
					// )
				} finally {
					session.clear()
				}
			}
		}
	}

	private data class MegolmId(val roomId: String, val senderKey: String, val sessionId: String)
	private val megolmSessionFlow = MutableSharedFlow<MegolmId>()

	suspend fun getMegolmSession(roomId: String, senderKey: String, sessionId: String): Flow<InboundGroupSession?> {
		return flow {
			val storedSession = store.getMegolmSession(roomId, senderKey, sessionId)
			emit(storedSession)
			if (storedSession == null) {
				megolmSessionFlow.first {
					it.roomId == roomId && it.senderKey == senderKey && it.sessionId == sessionId
				}
				val session = store.getMegolmSession(roomId, senderKey, sessionId)
				checkNotNull(session)
				emit(session)
			}
		}
	}


	private fun Utility.verifyEd25519Signature(deviceKeys: DeviceKeys) {
		verifyEd25519Signature(
			deviceKeys.userId,
			deviceKeys.deviceId,
			deviceKeys.keys.getValue("ed25519:${deviceKeys.deviceId}"),
			DeviceKeys.serializer(),
			deviceKeys
		)
	}
}
