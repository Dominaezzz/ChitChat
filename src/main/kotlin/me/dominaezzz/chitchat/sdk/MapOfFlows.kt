package me.dominaezzz.chitchat.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class MapOfFlows<K, V>(private val keyFlow: (K) -> SharedFlow<V>) {
	private val map = mutableMapOf<K, SharedFlow<V>>()
	private val semaphore = Semaphore(1)

	fun getFlow(key: K): Flow<V> {
		return flow {
			val flow = semaphore.withPermit {
				map.getOrPut(key) { keyFlow(key) }
			}
			emitAll(flow)
		}
	}
}
