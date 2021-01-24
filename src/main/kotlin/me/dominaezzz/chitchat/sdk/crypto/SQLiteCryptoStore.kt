package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.olm.Account
import io.github.matrixkt.olm.InboundGroupSession
import io.github.matrixkt.olm.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.dominaezzz.chitchat.db.*
import java.sql.Connection
import kotlin.random.Random

class SQLiteCryptoStore(
	private val dbSemaphore: Semaphore,
	private val random: Random
) : CryptoStore {
	private val emptyByteArray = byteArrayOf()

	private suspend inline fun <T> usingReadConnection(crossinline block: (Connection) -> T): T {
		return withContext(Dispatchers.IO) {
			usingConnection { block(it) }
		}
	}
	private suspend inline fun <T> usingWriteConnection(crossinline block: (Connection) -> T): T {
		return dbSemaphore.withPermit {
			withContext(Dispatchers.IO) {
				usingConnection { conn ->
					conn.transaction {
						block(conn)
					}
				}
			}
		}
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

	override suspend fun <T> usingAccount(block: (Account) -> T): T {
		val account = usingReadConnection { it.getAccount() }
		// NOTE: This will leak if `withContext` throws.
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
					conn.setValue("ACCOUNT", account.pickle(emptyByteArray))
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
}
