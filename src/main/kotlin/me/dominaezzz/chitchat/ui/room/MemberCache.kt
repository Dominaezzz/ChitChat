package me.dominaezzz.chitchat.ui.room

import androidx.compose.runtime.*
import io.github.matrixkt.models.events.contents.room.MemberContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.dominaezzz.chitchat.sdk.core.Room

private val LocalMemberCache = compositionLocalOf<MemberCache> { error("No member cache provided!") }

@Composable
fun MemberCache(room: Room, content: @Composable () -> Unit) {
	val cache = remember(room) { MemberCache(room) }
	LaunchedEffect(cache) { cache.load() }

	CompositionLocalProvider(LocalMemberCache provides cache) {
		content()
	}
}

@Composable
fun getMember(room: Room, userId: String): State<MemberContent?> {
	val cache = LocalMemberCache.current
	require(room == cache.room) {
		"Expected object for room ${cache.room.id} but got ${room.id}"
	}
	return cache.getMember(userId)
}

private class MemberCache(val room: Room) {
	private val stateMap = mutableMapOf<String, State<MemberContent?>>()
	private val stateChannel = Channel<Pair<String, MutableState<MemberContent?>>>(Channel.UNLIMITED)

	fun getMember(userId: String): State<MemberContent?> {
		val state = stateMap[userId]
		if (state != null) {
			return state
		}
		val newState = mutableStateOf<MemberContent?>(null)
		stateMap[userId] = newState
		val res = stateChannel.trySend(userId to newState)
		check(res.isSuccess)
		return newState
	}

	suspend fun load() {
		coroutineScope {
			for ((userId, state) in stateChannel) {
				val sessionFlow = room.getMember(userId)
				sessionFlow.onEach { state.value = it }.launchIn(this)
			}
		}
	}
}
