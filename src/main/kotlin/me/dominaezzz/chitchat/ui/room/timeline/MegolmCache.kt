package me.dominaezzz.chitchat.ui.room.timeline

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.matrixkt.olm.InboundGroupSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.dominaezzz.chitchat.sdk.crypto.CryptoManager

class MegolmCache(
	val roomId: String,
	val cryptoManager: CryptoManager
) : RememberObserver {
	private data class SessionKey(val senderKey: String, val sessionId: String)

	private val stateMap = mutableMapOf<SessionKey, State<InboundGroupSession?>>()
	private val stateChannel = Channel<Pair<SessionKey, MutableState<InboundGroupSession?>>>(Channel.UNLIMITED)

	fun getSession(senderKey: String, sessionId: String): State<InboundGroupSession?> {
		val key = SessionKey(senderKey, sessionId)
		val state = stateMap[key]
		if (state != null) {
			return state
		}
		val newState = mutableStateOf<InboundGroupSession?>(null)
		stateMap[key] = newState
		val res = stateChannel.trySend(key to newState)
		check(res.isSuccess)
		return newState
	}

	suspend fun load() {
		coroutineScope {
			while (true) {
				val (key, state) = stateChannel.receive()
				val sessionFlow = cryptoManager.getMegolmSession(roomId, key.senderKey, key.sessionId)
				sessionFlow.onEach { state.value = it }.launchIn(this)
			}
		}
	}

	override fun onAbandoned() {
		for (state in stateMap.values) {
			val session = state.value
			check(state is MutableState)
			state.value = null
			session?.clear()
		}
		stateMap.clear()
	}

	override fun onForgotten() {
		onAbandoned()
	}

	override fun onRemembered() {}
}
