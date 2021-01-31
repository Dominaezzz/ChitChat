package me.dominaezzz.chitchat.sdk.core

import io.github.matrixkt.MatrixClient
import kotlinx.coroutines.CoroutineScope
import me.dominaezzz.chitchat.sdk.core.internal.SyncClientImpl

fun SyncClient(
	scope: CoroutineScope,
	session: LoginSession,
	client: MatrixClient,
	store: SyncStore
): SyncClient {
	return SyncClientImpl(scope, session, client, store)
}
