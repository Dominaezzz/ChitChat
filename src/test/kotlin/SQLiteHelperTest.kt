import kotlinx.coroutines.runBlocking
import me.dominaezzz.chitchat.sdk.util.SQLiteHelper
import me.dominaezzz.chitchat.sdk.util.usingStatement
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteHelperTest {
	@get:Rule
	val folder = TemporaryFolder()

	@Test
	fun testCreationThenRead() {
		val helper = object : SQLiteHelper(folder.newFile().toPath(), 1) {
			override fun onCreate(connection: Connection) {
				connection.usingStatement { stmt ->
					stmt.execute("CREATE TABLE test(id INTEGER, value TEXT);")
				}
			}
		}

		runBlocking {
			helper.usingReadConnection { conn ->
				conn.usingStatement { stmt ->
					stmt.executeQuery("SELECT id, value FROM test;").use { rs ->
						assertFalse(rs.next())
					}
				}
			}
		}
	}

	@Test
	fun testCreationThenWrite() {
		val helper = object : SQLiteHelper(folder.newFile().toPath(), 1) {
			override fun onCreate(connection: Connection) {
				connection.usingStatement { stmt ->
					stmt.execute("CREATE TABLE test(id INTEGER, value TEXT);")
				}
			}
		}

		runBlocking {
			helper.usingWriteConnection { conn ->
				conn.usingStatement { stmt ->
					stmt.execute("INSERT INTO test(id, value) VALUES (5, 'LOL');")
				}
			}
		}
	}
}
