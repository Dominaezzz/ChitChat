package me.dominaezzz.chitchat.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import io.ktor.utils.io.core.*

private class Wrapper<T : Closeable>(val obj: T) : RememberObserver {
	override fun onAbandoned() {
		obj.close()
	}

	override fun onForgotten() {
		obj.close()
	}

	override fun onRemembered() {}
}

@Composable
fun <T : Closeable> rememberCloseable(calculation: () -> T): T {
	val wrapper = remember { Wrapper(calculation()) }
	return wrapper.obj
}

@Composable
fun <T : Closeable> rememberCloseable(key1: Any?, calculation: () -> T): T {
	val wrapper = remember(key1) { Wrapper(calculation()) }
	return wrapper.obj
}
