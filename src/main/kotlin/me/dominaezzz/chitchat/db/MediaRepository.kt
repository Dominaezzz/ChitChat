@file:Suppress("BlockingMethodInNonBlockingContext")

package me.dominaezzz.chitchat.db

import io.github.matrixkt.clientserver.api.GetContent
import io.github.matrixkt.client.rpc
import io.ktor.client.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume

class MediaRepository(
	private val client: HttpClient,
	private val mediaDir: Path
) : Closeable {
	private val scope = CoroutineScope(SupervisorJob())

	private val slowTimeoutMillis: Long = 5_000
	private val longTimeoutMillis: Long = 1000_000

	private val serverSemaphore = Semaphore(5)
	private val ioSemaphore = Semaphore(20)

	// Set of files being written to by a download.
	private val transientFilesSemaphore = Semaphore(1)
	private val transientFiles = mutableSetOf<Path>()

	private val jobQueue = Channel<Pair<MediaIdentifier, CancellableContinuation<ByteArray>>>(
		capacity = Channel.UNLIMITED,
		onUndeliveredElement = { (_, cont) -> cont.cancel() }
	)
	private val slowJobQueue = Channel<Pair<MediaIdentifier, CancellableContinuation<ByteArray>>>(
		capacity = Channel.UNLIMITED,
		onUndeliveredElement = { (_, cont) -> cont.cancel() } // TODO: Cancel duplicates too.
	)

	private val duplicatesSemaphore = Semaphore(1)
	private val duplicates = mutableMapOf<MediaIdentifier, MutableList<CancellableContinuation<ByteArray>>>()

	private val downloaderJob = scope.launch(start = CoroutineStart.LAZY) { runDownloader() }

	suspend fun getContent(uri: URI): ByteArray {
		require(uri.scheme == "mxc") // TODO: This should be handled higher up.

		val mediaId = MediaIdentifier(uri.host, uri.path.removePrefix("/"))

		val file = getMediaFile(mediaId)
		// Check if file has been downloaded, transient files are downloads in progress.
		val fileIsDownloaded = transientFilesSemaphore.withPermit { Files.exists(file) && file !in transientFiles }
		if (fileIsDownloaded) {
			return withContext(Dispatchers.IO) {
				Files.readAllBytes(file)
			}
		}

		// Either file has not been downloaded or it is being downloaded.
		// Regardless we push request on the queue.

		downloaderJob.start()

		return suspendCancellableCoroutine {
			val sendResult = jobQueue.trySend(mediaId to it)
			if (!sendResult.isSuccess) {
				it.cancel()
			}
		}
	}

	private suspend fun runDownloader() {
		coroutineScope {
			// Coroutine for slow downloads
			launch {
				for ((mediaInfo, continuation) in slowJobQueue) {
					suspend fun consumeContinuations(block: (CancellableContinuation<ByteArray>) -> Unit) {
						block(continuation)
						consumeDuplicates(mediaInfo, block)
					}

					val request = GetContent(GetContent.Url(mediaInfo.serverName, mediaInfo.mediaId, allowRemote = true))

					val file = getMediaFile(mediaInfo)
					val result = runCatching {
						val data = client.rpc(request) {
							timeout {
								socketTimeoutMillis = longTimeoutMillis
							}
						}
						tryWriteToFile(file, data)
						data
					}
					result.fold(
						{ data -> consumeContinuations { it.resume(data) } },
						{ e -> consumeContinuations { it.cancel(e) } }
					)
				}
			}

			for (downloadRequest in jobQueue) {
				val (mediaInfo, continuation) = downloadRequest
				if (continuation.isCancelled) continue

				val isDeduplicated = withContext(NonCancellable) {
					duplicatesSemaphore.withPermit {
						val list = duplicates[mediaInfo]
						if (list != null) {
							list.add(continuation)
						} else {
							duplicates[mediaInfo] = mutableListOf()
							false
						}
					}
				}
				if (isDeduplicated) continue

				suspend fun consumeContinuations(block: (CancellableContinuation<ByteArray>) -> Unit) {
					block(continuation)
					consumeDuplicates(mediaInfo, block)
				}

				val file = getMediaFile(mediaInfo)
				val fileIsDownloaded = Files.exists(file) // No need to check transientFiles because de-dup.
				if (fileIsDownloaded) {
					// launch should ignore cancellation
					launch(start = CoroutineStart.UNDISPATCHED) {
						val result = runCatching {
							ioSemaphore.withPermit {
								withContext(Dispatchers.IO) {
									Files.readAllBytes(file)
								}
							}
						}
						result.fold(
							{ data -> consumeContinuations { it.resume(data) } },
							{ e -> consumeContinuations { it.cancel(e) } }
						)
					}
				} else {
					// launch should ignore cancellation
					launch(start = CoroutineStart.UNDISPATCHED) {
						val request = GetContent(GetContent.Url(mediaInfo.serverName, mediaInfo.mediaId, allowRemote = true))
						val data = try {
							serverSemaphore.withPermit {
								client.rpc(request) {
									timeout {
										socketTimeoutMillis = slowTimeoutMillis
									}
								}
							}
						} catch (_: SocketTimeoutException) {
							println("$mediaInfo has been moved to the slow queue")
							slowJobQueue.send(downloadRequest)
							return@launch
						} catch (e: Exception) {
							consumeContinuations { it.cancel(e) }
							return@launch
						}

						try {
							tryWriteToFile(file, data)
						} catch (e: CancellationException) {
							consumeContinuations { it.cancel(e) }
							return@launch
						}
						consumeContinuations { it.resume(data) }
					}
				}
			}
		}
	}

	private suspend fun tryWriteToFile(file: Path, data: ByteArray) {
		transientFilesSemaphore.withPermit { transientFiles.add(file) }
		try {
			withContext(Dispatchers.IO) {
				Files.createDirectories(file.parent)
				Files.write(file, data)
			}
		} catch (e: Exception) {
			e.printStackTrace()
			withContext(NonCancellable + Dispatchers.IO) {
				Files.deleteIfExists(file)
			}
		} finally {
			withContext(NonCancellable) { // Transient file *MUST* be removed.
				transientFilesSemaphore.withPermit { transientFiles.remove(file) }
			}
		}
	}

	private suspend fun consumeDuplicates(
		mediaInfo: MediaIdentifier,
		block: (CancellableContinuation<ByteArray>) -> Unit)
	{
		val continuations = withContext(NonCancellable) {
			duplicatesSemaphore.withPermit { duplicates.remove(mediaInfo) }
		}
		continuations?.forEach(block)
	}

	override fun close() {
		jobQueue.close()
		slowJobQueue.close()
		scope.cancel()
	}

	private fun getMediaFile(mediaIdentifier: MediaIdentifier): Path {
		return mediaDir.resolve(mediaIdentifier.serverName).resolve(mediaIdentifier.mediaId)
	}

	private data class MediaIdentifier(
		val serverName: String,
		val mediaId: String
	)
}
