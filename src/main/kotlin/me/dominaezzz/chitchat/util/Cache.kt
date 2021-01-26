package me.dominaezzz.chitchat.util

import androidx.compose.runtime.RememberObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Cache<Key, Value>(private val loadData: suspend (Key) -> Value) : RememberObserver {
	private val scope = CoroutineScope(SupervisorJob())

	private val values = mutableMapOf<Key, Value>()
	private val valuesSemaphore = Semaphore(1)

	private val valueJobs = mutableMapOf<Key, Job>()
	private val valueJobsSemaphore = Semaphore(1)

	suspend fun getData(key: Key): Value {
		// If we have the value cached return it.
		valuesSemaphore.withPermit {
			val value = values[key]
			if (value != null) {
				return value
			}
		}

		val valueJob = valueJobsSemaphore.withPermit {
			// Is there a job to load up the value?
			val valueJob = valueJobs[key]
			if (valueJob != null) {
				// Get the job
				valueJob
			} else {
				// Check if job finished
				valuesSemaphore.withPermit {
					val value = values[key]
					if (value != null) {
						return value
					}
				}

				val job = scope.launch {
					val value = loadData(key)
					valuesSemaphore.withPermit { values[key] = value }
					valueJobsSemaphore.withPermit { valueJobs.remove(key) }
				}
				valueJobs[key] = job
				job
			}
		}

		valueJob.join()

		return valuesSemaphore.withPermit { values.getValue(key) }
	}

	override fun onRemembered() {}

	override fun onForgotten() {
		scope.cancel()
	}

	override fun onAbandoned() {
		scope.cancel()
	}
}
