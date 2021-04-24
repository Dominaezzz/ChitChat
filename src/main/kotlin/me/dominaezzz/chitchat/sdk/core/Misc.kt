package me.dominaezzz.chitchat.sdk.core

import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import me.dominaezzz.chitchat.sdk.core.internal.SyncClientImpl

fun SyncClient(
	scope: CoroutineScope,
	session: LoginSession,
	client: HttpClient,
	store: SyncStore
): SyncClient {
	return SyncClientImpl(scope, session, client, store)
}
