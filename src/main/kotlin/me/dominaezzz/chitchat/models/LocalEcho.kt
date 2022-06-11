package me.dominaezzz.chitchat.models

import io.github.matrixkt.client.rpc
import io.github.matrixkt.clientserver.api.SendMessage
import io.ktor.client.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import me.dominaezzz.chitchat.sdk.core.LoginSession
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocalEcho(
	private val roomId: String,
	private val client: HttpClient,
	private val session: LoginSession
) : Closeable {
	private val scope = CoroutineScope(SupervisorJob() + CoroutineName("DraftManager"))

	private val nonce = "TXNID_${System.currentTimeMillis()}_"
	private var index = 0

	private val messageJob = scope.launch(start = CoroutineStart.LAZY) { processMessages() }

	private val _pendingMessages = MutableStateFlow<List<Pending>>(emptyList())
	val pendingMessages: StateFlow<List<Pending>> = _pendingMessages.asStateFlow()

	private val _sentMessages = MutableSharedFlow<Sent?>()
	val sentMessages: Flow<Sent> = _sentMessages.filterNotNull()

	private suspend fun processMessages() {
		val maxBackOff = 5.minutes

		while (true) {
			val message = pendingMessages.first { it.isNotEmpty() }.first()

			val request = SendMessage(SendMessage.Url(roomId, message.type, message.txnId), message.content)

			var backOff = 3.seconds
			while (true) {
				try {
					val response = client.rpc(request, session.accessToken)
					val sent = Sent(message.roomId, message.type, message.txnId, message.content, response.eventId)
					_sentMessages.emit(sent)
					// This is to wait for all subscribers to process (and not just receive).
					_sentMessages.emit(null)
					_pendingMessages.update { it - message }
					break
				} catch (e: Exception) {
					e.printStackTrace()
					if (backOff >= maxBackOff) {
						println("Giving up on sending messages!")
						// Give up!
						awaitCancellation()
						// TODO: Allow user to restart this loop.
					}
					delay(backOff)
					backOff *= 2
				}
			}
		}
	}

	fun sendMessage(type: String, content: JsonObject) {
		messageJob.start()

		val txnId = nonce + index++
		val message = Pending(roomId, type, txnId, content)

		_pendingMessages.update { it + message }
	}

	override fun close() {
		scope.cancel()
		_pendingMessages.value = emptyList()
	}

	class Pending(
		val roomId: String,
		val type: String,
		val txnId: String,
		val content: JsonObject
	)

	class Sent(
		val roomId: String,
		val type: String,
		val txnId: String,
		val content: JsonObject,
		val eventId: String
	)
}
