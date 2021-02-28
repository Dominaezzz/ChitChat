package me.dominaezzz.chitchat.util

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntSize
import io.github.matrixkt.models.EncryptedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.dominaezzz.chitchat.db.ContentRepository
import me.dominaezzz.chitchat.sdk.crypto.Attachments
import me.dominaezzz.chitchat.ui.AppModelAmbient
import org.jetbrains.skija.Image
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.math.min

private val LocalImageCache = compositionLocalOf<Cache<URI, ImageBitmap>> { error("No image cache specified") }
private val LocalEncryptedImageCache = compositionLocalOf<Cache<EncryptedFile, ImageBitmap>> { error("No image cache specified") }
private val LocalIconCache = compositionLocalOf<ImageIconCache> { error("No icon cache specified") }

class ImageIconCache(private val contentRepository: ContentRepository) {
	private val iconSize = 100f

	private val stateMap = mutableMapOf<URI, State<ImageBitmap?>>()
	private val stateChannel = Channel<Pair<URI, MutableState<ImageBitmap?>>>(Channel.UNLIMITED)

	fun getImage(uri: URI): State<ImageBitmap?> {
		val state = stateMap[uri]
		if (state != null) {
			return state
		}
		val newState = mutableStateOf<ImageBitmap?>(null)
		stateMap[uri] = newState
		val res = stateChannel.offer(uri to newState)
		check(res)
		return newState
	}

	suspend fun load() {
		coroutineScope {
			repeat(4) {
				launch {
					for ((uri, state) in stateChannel) {
						try {
							val bytes = contentRepository.getContent(uri)
							val srcImage = withContext(Dispatchers.Default) {
								Image.makeFromEncoded(bytes).asImageBitmap()
							}
							val scale = iconSize / min(srcImage.width, srcImage.height)
							state.value = if (scale > 0.9) {
								srcImage
							} else {
								withContext(Dispatchers.Default) {
									scaleImage(srcImage, scale)
								}
							}
						} catch (e: Exception) {
							e.printStackTrace()
						}
					}
				}
			}
		}
	}
}

@Composable
fun ImageCache(content: @Composable () -> Unit) {
	val contentRepo = AppModelAmbient.current.contentRepository
	val imageCache = remember(contentRepo) {
		Cache<URI, ImageBitmap> {
			val bytes = contentRepo.getContent(it)
			withContext(Dispatchers.Default) {
				Image.makeFromEncoded(bytes).asImageBitmap()
			}
		}
	}
	val iconCache = remember(contentRepo) { ImageIconCache(contentRepo) }
	val encImageCache = remember(contentRepo) {
		Cache<EncryptedFile, ImageBitmap> {
			val bytes = contentRepo.getContent(URI(it.url))
			val result = ByteArrayOutputStream(bytes.size)
			Attachments.decrypt(bytes.inputStream(), result, it)
			val decryptedBytes = result.toByteArray()
			withContext(Dispatchers.Default) {
				Image.makeFromEncoded(decryptedBytes).asImageBitmap()
			}
		}
	}

	LaunchedEffect(iconCache) { iconCache.load() }

	CompositionLocalProvider(
		LocalImageCache provides imageCache,
		LocalEncryptedImageCache provides encImageCache,
		LocalIconCache provides iconCache
	) {
		content()
	}
}

private fun scaleImage(srcImage: ImageBitmap, scale: Float): ImageBitmap {
	val dstImage = ImageBitmap(
		(srcImage.width * scale).toInt(),
		(srcImage.height * scale).toInt(),
		srcImage.config,
		srcImage.hasAlpha,
		srcImage.colorSpace
	)

	val canvas = Canvas(dstImage)
	canvas.drawImageRect(
		srcImage,
		dstSize = IntSize(dstImage.width, dstImage.height),
		paint = Paint().apply { filterQuality = FilterQuality.High }
	)
	return dstImage
}

@Composable
fun loadEncryptedImage(file: EncryptedFile): ImageBitmap? {
	val iconCache = LocalEncryptedImageCache.current
	return produceState<ImageBitmap?>(null, file) {
		value = null
		value = runCatching { iconCache.getData(file) }.getOrNull()
	}.value
}

@Composable
fun loadImage(uri: URI): ImageBitmap? {
	val iconCache = LocalImageCache.current
	return produceState<ImageBitmap?>(null, uri) {
		value = null
		value = runCatching { iconCache.getData(uri) }.getOrNull()
	}.value
}

@Composable
fun loadIcon(uri: URI): ImageBitmap? {
	val iconCache = LocalIconCache.current
	return iconCache.getImage(uri).value
}
