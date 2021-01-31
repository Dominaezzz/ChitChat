package me.dominaezzz.chitchat.sdk.core.internal

import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement


internal fun <T> Flow<JsonElement?>.decodeJson(deserializationStrategy: DeserializationStrategy<T>): Flow<T?> {
	return map { content ->
		if (content != null) {
			try {
				MatrixJson.decodeFromJsonElement(deserializationStrategy, content)
			} catch (e: Exception) {
				null
			}
		} else {
			null
		}
	}
}

@ExperimentalCoroutinesApi
internal fun <T> Flow<T?>.coalesce(other: Flow<T>): Flow<T> {
	return distinctUntilChanged { old, new -> old == null && new == null }
		.transformLatest {
			if (it != null) {
				// TODO: Will "Latest" wrongly cancel this branch?
				emit(it)
			} else {
				emitAll(other)
			}
		}
}
