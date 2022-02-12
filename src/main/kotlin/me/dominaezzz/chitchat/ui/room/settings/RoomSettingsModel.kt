package me.dominaezzz.chitchat.ui.room.settings

import androidx.compose.runtime.*
import io.github.matrixkt.api.GetRoomIdByAlias
import io.github.matrixkt.models.MatrixError
import io.github.matrixkt.models.MatrixException
import io.github.matrixkt.models.events.contents.room.*
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import me.dominaezzz.chitchat.sdk.core.*

class RoomSettingsModel(
	private val scope: CoroutineScope,
	private val roomId: String,
	private val client: HttpClient,
	private val syncClient: SyncClient,
	private val session: LoginSession
) {
	@OptIn(ExperimentalCoroutinesApi::class)
	private fun <T> state(block: Room.() -> Flow<T?>): StateFlow<T?> {
		return syncClient.joinedRooms.map { it[roomId] }
			.distinctUntilChanged()
			.flatMapLatest { room ->
				if (room != null) {
					room.block()
				} else {
					flowOf<T?>(null)
				}
			}
			.stateIn(scope, SharingStarted.Lazily, null)
	}

	private inline fun <reified T> getState(type: String): StateFlow<T?> {
		// TODO: Consider doing `stateKey: String = ""` in the SDK.
		return state { getState<T>(type, "") }
	}

	val createContent: StateFlow<CreateContent?> = getState("m.room.create")

	fun canUpdateState(type: String): Flow<Boolean> {
		return powerLevelContent.combine(createContent) { powerLevels, create ->
			val userPowerLevel = when {
				powerLevels != null -> powerLevels.run { users.getOrDefault(session.userId, usersDefault) }
				create?.creator == session.userId -> 100
				else -> 0
			}
			val eventPowerLevel = powerLevels?.run { events.getOrDefault(type, stateDefault) } ?: 0
			userPowerLevel >= eventPowerLevel
		}
	}

	val nameContent: StateFlow<NameContent?> = state { name }
	var name: String? by mutableStateOf(null)

	val topicContent: StateFlow<TopicContent?> = state { topic }
	var topic: String? by mutableStateOf(null)

	val canonicalAliasContent: StateFlow<CanonicalAliasContent?> = state { canonicalAlias }
	var canonicalAlias: String? by mutableStateOf(null)
	var alternativeAliases: PersistentList<String>? by mutableStateOf(null)
	@OptIn(ExperimentalCoroutinesApi::class)
	val aliasPointsToRoom: StateFlow<Boolean?> = snapshotFlow { canonicalAlias }
		.transformLatest { alias ->
			emit(null)
			if (alias == null) {
				return@transformLatest
			}

			val request = GetRoomIdByAlias(GetRoomIdByAlias.Url(alias))
			try {
				val response = client.rpc(request)
				emit(response.roomId == roomId)
			} catch (e: MatrixException) {
				if (e.error is MatrixError.NotFound) {
					emit(false)
				} else {
					e.printStackTrace()
				}
			} catch (e: Exception) {
			    e.printStackTrace()
			}
		}
		.stateIn(scope, SharingStarted.WhileSubscribed(), null)

	val joinRulesContent: StateFlow<JoinRulesContent?> = state { joinRules }
	var joinRule: JoinRule? by mutableStateOf(null)

	val guestAccessContent: StateFlow<GuestAccessContent?> = state { guestAccess }
	var guestAccess: GuestAccess? by mutableStateOf(null)

	val powerLevelContent: StateFlow<PowerLevelsContent?> = getState("m.room.power_levels")
	val powerLevelsModel = PowerLevelsModel()

	@OptIn(ExperimentalCoroutinesApi::class)
	fun getMember(userId: String): Flow<MemberContent?> {
		return syncClient.joinedRooms.map { it[roomId] }
			.distinctUntilChanged()
			.flatMapLatest { it?.getMember(userId) ?: flowOf(null) }
	}
}
