package me.dominaezzz.chitchat.sdk.crypto

import io.github.matrixkt.olm.Account
import io.github.matrixkt.olm.InboundGroupSession
import io.github.matrixkt.olm.Session

interface CryptoStore {
	suspend fun <T> usingAccount(block: (Account) -> T): T

	suspend fun <T> modifyAccount(block: (Account) -> T): T

	suspend fun getInboundSessions(identityKey: String): List<Session>

	suspend fun storeInboundSession(identityKey: String, session: Session)

	suspend fun markAsRecentlyUsed(sessionId: String): Boolean

	suspend fun storeGroupSession(
		roomId: String,
		senderKey: String,
		session: InboundGroupSession,
		ed25119Key: String,
		forwardingChain: List<String> = emptyList()
	)
}
