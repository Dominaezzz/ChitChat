package me.dominaezzz.chitchat.sdk.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.dominaezzz.chitchat.db.usingStatement
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

abstract class SQLiteHelper(
	private val databaseFile: Path,
	private val version: Int
) {
	private val initSemaphore = Semaphore(1)
	private val writeSemaphore = Semaphore(1)

	init {
		require(version > 0)
	}

	protected abstract fun onCreate(connection: Connection)
	protected open fun onUpgrade(connection: Connection, oldVersion: Int, newVersion: Int) {
		TODO()
	}
	protected open fun getConfig(): SQLiteConfig {
		return SQLiteConfig().apply {
			enforceForeignKeys(true)
			setJournalMode(SQLiteConfig.JournalMode.WAL)
		}
	}

	private var Connection.version: Int
		get() = usingStatement { stmt -> stmt.executeQuery("PRAGMA user_version;").use { check(it.next()); it.getInt(1) } }
		set(value) { usingStatement { stmt -> stmt.executeUpdate("PRAGMA user_version = $value;") } }

	private suspend fun openConnection(): Connection {
		val config = getConfig()

		val connection = DriverManager.getConnection(
			"jdbc:sqlite:${databaseFile.toAbsolutePath()}",
			config.toProperties()
		)

		if (connection.version != version) {
			try {
				initSemaphore.withPermit {
					connection.autoCommit = false
					val currentVersion = connection.version
					if (currentVersion != version) {
						when {
							currentVersion == 0 -> onCreate(connection)
							connection.version > version -> onUpgrade(connection, currentVersion, version)
							else -> TODO("Downgrade not supported")
						}
						connection.version = version
					}
					connection.autoCommit = true
				}
			} catch (e: Exception) {
				connection.close()
				throw e
			}
		}

		return connection
	}

	private suspend inline fun <T> usingConnection(block: (Connection) -> T): T {
		return openConnection().use(block)
	}

	suspend fun <T> usingReadConnection(block: (Connection) -> T): T {
		return withContext(Dispatchers.IO) {
			usingConnection(block)
		}
	}
	suspend fun <T> usingWriteConnection(block: (Connection) -> T): T {
		return writeSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					conn.autoCommit = false
					block(conn)
				}
			}
		}
	}
}
