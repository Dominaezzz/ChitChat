package me.dominaezzz.chitchat.util

import kotlinx.coroutines.flow.MutableStateFlow

inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
	while (true) {
		val prevValue = value
		val nextValue = block(prevValue)
		if (compareAndSet(prevValue, nextValue)) {
			return
		}
	}
}
