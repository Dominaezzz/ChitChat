import io.github.matrixkt.api.GetRoomEvents
import io.github.matrixkt.models.events.SyncEvent
import io.github.matrixkt.models.sync.*
import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.dominaezzz.chitchat.db.getSerializable
import me.dominaezzz.chitchat.sdk.core.SQLiteSyncStore
import me.dominaezzz.chitchat.sdk.util.toMatrixEvent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class SQLiteSyncStoreTest {
	@get:Rule
	val folder = TemporaryFolder()

	@Test
	fun testBackPagination() {
		val roomJsonStr = javaClass.getResource("room_1.json").readText()
		val roomJson = MatrixJson.parseToJsonElement(roomJsonStr).jsonObject
		val roomId = roomJson["room_id"]!!.jsonPrimitive.content
		val events = MatrixJson.decodeFromJsonElement(ListSerializer(SyncEvent.serializer()), roomJson["events"]!!)

		val sync = SyncResponse(
			nextBatch = "DOESN'T MATTER",
			rooms = Rooms(
				join = mapOf(roomId to JoinedRoom(
					summary = RoomSummary(),
					state = State(events.dropLast(20).filter { it.stateKey != null }.associateBy { it.type to it.stateKey!! }.values.toList()),
					timeline = Timeline(events.takeLast(20), limited = true, prevBatch = "PREV")
				))
			)
		)
		val messages = GetRoomEvents.Response(
			start = "PREV",
			chunk = events.dropLast(20).map { it.toMatrixEvent(roomId) }.asReversed()
		)

		val store = SQLiteSyncStore(folder.newFile().toPath())
		runBlocking {
			store.storeSync(sync, null)
			store.storeTimelineEvents(roomId, messages)

			val storedEvents = store.getTimelineEvents(roomId, 0)

			assertEquals(events.size, storedEvents.size)
			for ((event, storedEvent) in events.zip(storedEvents)) {
				assertEquals(event, storedEvent)
			}
		}
	}

	@Test
	fun testTimelineStitch() {
		val roomJsonStr = javaClass.getResource("room_1.json").readText()
		val roomJson = MatrixJson.parseToJsonElement(roomJsonStr).jsonObject
		val roomId = roomJson["room_id"]!!.jsonPrimitive.content
		val events = MatrixJson.decodeFromJsonElement(ListSerializer(SyncEvent.serializer()), roomJson["events"]!!)

		val initialSync = SyncResponse(
			nextBatch = "DOESN'T MATTER",
			rooms = Rooms(
				join = mapOf(roomId to JoinedRoom(
					summary = RoomSummary(),
					state = State(events.take(30).filter { it.stateKey != null }.associateBy { it.type to it.stateKey!! }.values.toList()),
					timeline = Timeline(events.drop(30).take(30), limited = true, prevBatch = "PREV")
				))
			)
		)
		val incrementalSync = SyncResponse(
			nextBatch = "ANOTHER BATCH",
			rooms = Rooms(
				join = mapOf(roomId to JoinedRoom(
					summary = RoomSummary(),
					state = State(events.drop(60).dropLast(20).filter { it.stateKey != null }.associateBy { it.type to it.stateKey!! }.values.toList()),
					timeline = Timeline(events.takeLast(20), limited = true, prevBatch = "PREV2")
				))
			)
		)

		val timelineGap = events.dropLast(20).drop(60).asReversed()
		val timelineState = timelineGap.filter { it.stateKey != null }
			.map { it.type to it.stateKey!! }.toSet()
		val messages = GetRoomEvents.Response(
			start = "PREV2",
			chunk = timelineGap.map { it.toMatrixEvent(roomId) },
			state = events.take(60)
				.filter { it.stateKey != null }
				.associateBy { it.type to it.stateKey!! }
				.filterKeys { it in timelineState }
				.values
				.map { it.toMatrixEvent(roomId) }
		)

		val store = SQLiteSyncStore(folder.newFile().toPath())
		runBlocking {
			store.storeSync(initialSync, null)
			store.storeSync(incrementalSync, initialSync.nextBatch)

			store.storeTimelineEvents(roomId, messages)

			val storedEvents = store.getTimelineEvents(roomId, 0)

			val expected = events.drop(60)
			assertEquals(expected.size, storedEvents.size)
			for ((event, storedEvent) in expected.zip(storedEvents)) {
				assertEquals(event, storedEvent)
			}
		}
	}

	@OptIn(ExperimentalStdlibApi::class)
	private suspend fun SQLiteSyncStore.getTimelineEvents(roomId: String, timelineId: Int): List<SyncEvent> {
		return read { conn ->
			val query = """
				SELECT json FROM room_events
				WHERE roomId = ? AND timelineId = ? AND timelineOrder IS NOT NULL
				ORDER BY timelineOrder;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setInt(2, timelineId)
				stmt.executeQuery().use { rs ->
					buildList {
						while (rs.next()) {
							add(rs.getSerializable(1, SyncEvent.serializer()))
						}
					}
				}
			}
		}
	}
}
