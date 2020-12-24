package me.dominaezzz.chitchat.sdk

import io.github.matrixkt.utils.MatrixJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
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
	return transformLatest {
		if (it != null) {
			emit(it)
		} else {
			emitAll(other)
		}
	}
}
