package me.dominaezzz.chitchat.db

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skija.Image
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO
import kotlin.math.min

class IconLoader(private val repo: ContentRepository) {
	private val iconSize = 100f

	private val images = mutableMapOf<URI, ImageBitmap>()
	private val imagesSemaphore = Semaphore(1)

	private val imageJobs = mutableMapOf<URI, Job>()
	private val imageJobsSemaphore = Semaphore(1)

	private val iconLoaderScope = CoroutineScope(SupervisorJob())

	suspend fun loadIcon(uri: URI): ImageBitmap {
		// If we have the image cached return it.
		imagesSemaphore.withPermit {
			val image = images[uri]
			if (image != null) {
				return image
			}
		}

		val imageJob = imageJobsSemaphore.withPermit {
			// Is there a job to load up the image?
			val imageJob = imageJobs[uri]
			if (imageJob != null) {
				// Get the job
				imageJob
			} else {
				// Check if job finished
				imagesSemaphore.withPermit {
					val image = images[uri]
					if (image != null) {
						return image
					}
				}

				val job = iconLoaderScope.launch {
					val image = loadIconReal(uri)
					imagesSemaphore.withPermit { images[uri] = image }
					imageJobsSemaphore.withPermit { imageJobs.remove(uri) }
				}
				imageJobs[uri] = job
				job
			}
		}

		imageJob.join()

		return imagesSemaphore.withPermit { images.getValue(uri) }
	}

	private suspend fun loadIconReal(uri: URI): ImageBitmap {
		val rawImageData = repo.getContent(uri)

		return withContext(Dispatchers.Default) {
			val srcImage = ImageIO.read(rawImageData.inputStream())

			val scale = iconSize / min(srcImage.width, srcImage.height)
			if (scale > 0.9) {
				return@withContext Image.makeFromEncoded(rawImageData).asImageBitmap()
			}

			val dstImage = BufferedImage(
				(srcImage.width * scale).toInt(),
				(srcImage.height * scale).toInt(),
				srcImage.type
			)

			val transform = AffineTransform.getScaleInstance(scale.toDouble(), scale.toDouble())
			val transformOp = AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR)

			transformOp.filter(srcImage, dstImage)

			val output = ByteArrayOutputStream((rawImageData.size * scale).toInt() /* Calm down it's just a hint */)
			ImageIO.write(dstImage, "PNG", output)

			Image.makeFromEncoded(output.toByteArray()).asImageBitmap()
		}
	}
}
