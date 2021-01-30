package me.dominaezzz.chitchat.util

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.dominaezzz.chitchat.ui.ContentRepoAmbient
import org.jetbrains.skija.Image
import java.net.URI
import kotlin.math.min

private val AmbientImageCache = ambientOf<Cache<URI, ImageBitmap>> { error("No image cache specified") }
private val AmbientIconCache = ambientOf<Cache<URI, ImageBitmap>> { error("No icon cache specified") }

@Composable
fun ImageCache(content: @Composable () -> Unit) {
	val contentRepo = ContentRepoAmbient.current
	val imageCache = remember(contentRepo) {
		Cache<URI, ImageBitmap> {
			val bytes = contentRepo.getContent(it)
			withContext(Dispatchers.Default) {
				Image.makeFromEncoded(bytes).asImageBitmap()
			}
		}
	}

	Providers(AmbientImageCache provides imageCache) {
		content()
	}
}

@Composable
fun IconCache(content: @Composable () -> Unit) {
	val iconSize = 100f

	ImageCache {
		val imageCache = AmbientImageCache.current
		val iconCache = remember(imageCache) {
			Cache<URI, ImageBitmap> { uri ->
				val srcImage = imageCache.getData(uri)
				val scale = iconSize / min(srcImage.width, srcImage.height)
				if (scale > 0.9) {
					srcImage
				} else {
					withContext(Dispatchers.Default) {
						scaleImage(srcImage, scale)
					}
				}
			}
		}

		Providers(AmbientIconCache provides iconCache) {
			content()
		}
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
fun loadImage(uri: URI): ImageBitmap? {
	val iconCache = AmbientImageCache.current
	return produceState<ImageBitmap?>(null, uri) {
		value = null
		value = runCatching { iconCache.getData(uri) }.getOrNull()
	}.value
}

@Composable
fun loadIcon(uri: URI): ImageBitmap? {
	val iconCache = AmbientIconCache.current
	return produceState<ImageBitmap?>(null, uri) {
		value = null
		value = runCatching { iconCache.getData(uri) }.getOrNull()
	}.value
}
