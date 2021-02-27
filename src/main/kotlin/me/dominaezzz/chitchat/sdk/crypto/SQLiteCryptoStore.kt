package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.olm.Account
import io.github.matrixkt.olm.InboundGroupSession
import io.github.matrixkt.olm.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.dominaezzz.chitchat.db.*
import me.dominaezzz.chitchat.sdk.util.SQLiteHelper
import java.nio.file.Path
import java.sql.Connection
import kotlin.random.Random

class SQLiteCryptoStore(
	private val databaseFile: Path,
	private val random: Random
) : CryptoStore {
	private val emptyByteArray = byteArrayOf()

	private val helper = object : SQLiteHelper(databaseFile, 1) {
		override fun onCreate(connection: Connection) {
			connection.usingStatement { stmt ->
				stmt.execute("""
					CREATE TABLE key_value_store
					(
						key   TEXT PRIMARY KEY NOT NULL,
						value TEXT
					);
				""")
				stmt.execute("""
					CREATE TABLE olm_sessions
					(
						sessionId         TEXT    PRIMARY KEY NOT NULL,
						identityKey       TEXT    NOT NULL,
						pickle            TEXT    NOT NULL,
						isOutbound        BOOLEAN NOT NULL,
						lastSuccessfulUse INTEGER NOT NULL DEFAULT (STRFTIME('%s', 'now'))
					);
				""")
				stmt.execute("""
					CREATE TABLE megolm_sessions
					(
						roomId           TEXT NOT NULL,
						senderKey        TEXT NOT NULL,
						sessionId        TEXT NOT NULL,
						pickle           TEXT NOT NULL,
						ed25519Key       TEXT NOT NULL,
						forwardingChain  TEXT NOT NULL DEFAULT (JSON_ARRAY()),
						PRIMARY KEY (roomId, senderKey, sessionId)
					);
				""")
			}
		}
	}

	private suspend fun <T> usingReadConnection(block: (Connection) -> T): T {
		return helper.usingReadConnection(block)
	}
	private suspend fun <T> usingWriteConnection(block: (Connection) -> T): T {
		return helper.usingWriteConnection(block)
	}

	private fun Connection.getAccount(): Account {
		val pickle = getValue("ACCOUNT")
		return if (pickle != null) {
			Account.unpickle(emptyByteArray, pickle)
		} else {
			// This seems like business logic that shouldn't be here.
			val account = Account(random)
			setValue("ACCOUNT", account.pickle(emptyByteArray))
			account
		}
	}

	private fun Connection.setAccount(account: Account) {
		setValue("ACCOUNT", account.pickle(emptyByteArray))
	}

	override suspend fun <T> usingAccount(block: (Account) -> T): T {
		// NOTE: This will leak if `withContext` throws.
		val account = usingReadConnection { it.getAccount() }
		try {
			return block(account)
		} finally {
			account.clear()
		}
	}

	override suspend fun <T> modifyAccount(block: (Account) -> T): T {
		return usingWriteConnection { conn ->
			conn.savepoint {
				val account = conn.getAccount()
				try {
					val res = block(account)
					// Only save account if block finished.
					conn.setAccount(account)
					res
				} finally {
					account.clear()
				}
			}
		}
	}

	override suspend fun getInboundSessions(identityKey: String): List<Session> {
		return usingReadConnection { conn ->
			val query = """
				SELECT pickle
				FROM olm_sessions
				WHERE identityKey = ? AND NOT isOutbound
				ORDER BY lastSuccessfulUse DESC;
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, identityKey)
				stmt.executeQuery().use { rs ->
					@OptIn(ExperimentalStdlibApi::class)
					buildList {
						while (rs.next()) {
							val pickle = rs.getString(1)
							val session = Session.unpickle(emptyByteArray, pickle)
							// NOTE: If one of these throws the previous ones will be leaked.
							add(session)
						}
					}
				}
			}
		}
	}

	override suspend fun storeInboundSession(identityKey: String, session: Session) {
		usingWriteConnection { conn ->
			val query = "INSERT INTO olm_sessions(sessionId, identityKey, pickle, isOutbound) VALUES (?, ?, ?, ?);"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, session.sessionId)
				stmt.setString(2, identityKey)
				stmt.setString(3, session.pickle(emptyByteArray))
				stmt.setBoolean(4, false)
				stmt.executeUpdate()
			}

			val account = conn.getAccount()
			try {
				account.removeOneTimeKeys(session)
				conn.setAccount(account)
			} finally {
				account.clear()
			}
		}
	}

	override suspend fun markAsRecentlyUsed(sessionId: String): Boolean {
		return usingWriteConnection { conn ->
			val query = "UPDATE olm_sessions SET lastSuccessfulUse = STRFTIME('%s', 'now') WHERE sessionId = ?;"
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, sessionId)
				stmt.executeUpdate() > 0
			}
		}
	}

	override suspend fun storeGroupSession(
		roomId: String,
		senderKey: String,
		session: InboundGroupSession,
		ed25119Key: String,
		forwardingChain: List<String>
	) {
		usingWriteConnection { conn ->
			val query = """
				INSERT INTO megolm_sessions(roomId, senderKey, sessionId, pickle, ed25519Key, forwardingChain)
				VALUES (?, ?, ?, ?, ?, ?);
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, senderKey)
				stmt.setString(3, session.sessionId)
				stmt.setString(4, session.pickle(emptyByteArray))
				stmt.setString(5, ed25119Key)
				stmt.setSerializable(6, ListSerializer(String.serializer()), forwardingChain)
				stmt.executeUpdate()
			}
		}
	}

	override suspend fun getMegolmSession(roomId: String, senderKey: String, sessionId: String): InboundGroupSession? {
		val pickle = usingReadConnection { conn ->
			val query = """
				SELECT pickle FROM megolm_sessions
				WHERE roomId = ? AND senderKey = ? AND sessionId = ?
			"""
			conn.prepareStatement(query).use { stmt ->
				stmt.setString(1, roomId)
				stmt.setString(2, senderKey)
				stmt.setString(3, sessionId)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getString(1)
					} else {
						null
					}
				}
			}
		}
		return pickle?.let { InboundGroupSession.unpickle(emptyByteArray, it) }
	}
}
