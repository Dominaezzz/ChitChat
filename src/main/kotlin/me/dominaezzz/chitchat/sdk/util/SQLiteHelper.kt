package me.dominaezzz.chitchat.sdk.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
		}
	}

	private var Connection.version: Int
		get() = usingStatement { stmt -> stmt.executeQuery("PRAGMA user_version;").use { check(it.next()); it.getInt(1) } }
		set(value) { usingStatement { stmt -> stmt.executeUpdate("PRAGMA user_version = $value;") } }

	private val jdbcUrl = "jdbc:sqlite:${databaseFile.toAbsolutePath()}"

	private fun runMigrations(conn: Connection) {
		conn.autoCommit = false
		val currentVersion = conn.version
		if (currentVersion != version) {
			when {
				currentVersion == 0 -> onCreate(conn)
				currentVersion > version -> onUpgrade(conn, currentVersion, version)
				else -> TODO("Downgrade not supported")
			}
			conn.version = version
		}
		conn.autoCommit = true
	}

	private suspend fun openConnection(readOnly: Boolean): Connection {
		val config = getConfig()
		config.setReadOnly(readOnly)
		if (!readOnly) {
			config.setJournalMode(SQLiteConfig.JournalMode.WAL)
		}

		val connection = DriverManager.getConnection(jdbcUrl, config.toProperties())

		if (connection.version != version) {
			try {
				initSemaphore.withPermit {
					if (readOnly) {
						config.setReadOnly(false)
						config.setLockingMode(SQLiteConfig.LockingMode.EXCLUSIVE)
						DriverManager.getConnection(jdbcUrl, config.toProperties()).use { conn ->
							runMigrations(conn)
						}
					} else {
						runMigrations(connection)
					}
				}
			} catch (e: Exception) {
				connection.close()
				throw e
			}
		}

		return connection
	}

	suspend fun <T> usingReadConnection(block: (Connection) -> T): T {
		return withContext(Dispatchers.IO) {
			openConnection(true).use(block)
		}
	}
	suspend fun <T> usingWriteConnection(block: (Connection) -> T): T {
		return writeSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				openConnection(false).use { conn ->
					conn.transaction {
						block(conn)
					}
				}
			}
		}
	}
}
