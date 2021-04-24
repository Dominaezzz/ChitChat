package me.dominaezzz.chitchat.db

import io.github.matrixkt.api.GetContent
import io.github.matrixkt.utils.rpc
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class ContentRepository(private val client: HttpClient, private val mediaDir: Path) {
	private val scope = CoroutineScope(SupervisorJob())
	private val serverSemaphore = Semaphore(2)
	private val ioSemaphore = Semaphore(5)

	suspend fun getContent(uri: URI): ByteArray {
		require(uri.scheme == "mxc") // TODO: This should be handled higher up.

		val serverName = uri.host
		val mediaId = uri.path.removePrefix("/")

		val mediaFile = mediaDir.resolve(serverName).resolve(mediaId)
		return if (Files.exists(mediaFile)) {
			ioSemaphore.withPermit {
				withContext(Dispatchers.IO) {
					Files.readAllBytes(mediaFile)
				}
			}
		} else {
			val data = serverSemaphore.withPermit {
				client.rpc(GetContent(GetContent.Url(serverName, mediaId, true)))
			}
			scope.launch(Dispatchers.Default) {
				ioSemaphore.withPermit {
					withContext(Dispatchers.IO) {
						Files.createDirectories(mediaFile.parent)
						Files.write(mediaFile, data)
					}
				}
			}
			data
		}
	}

	fun close() {
		scope.cancel()
	}
}
