package com.netpulse.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

data class SpeedTestUiState(
    val stage: String = "READY",
    val message: String = "अपनी इंटरनेट स्पीड मापने के लिए टेस्ट शुरू करें।",
    val progress: Float = 0f,
    val liveMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val downloadPeakMbps: Double = 0.0,
    val uploadPeakMbps: Double = 0.0,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L
)

data class SpeedTestResult(
    val pingMs: Double,
    val jitterMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val downloadPeakMbps: Double,
    val uploadPeakMbps: Double,
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    val durationMs: Long
)

private data class TransferResult(
    val averageMbps: Double,
    val peakMbps: Double,
    val bytes: Long,
    val durationMs: Long
)

object SpeedTestEngine {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val DOWNLOAD_BYTES = 25_000_000
    private const val UPLOAD_BYTES = 16 * 1024 * 1024
    private const val PARALLEL_CONNECTIONS = 2

    suspend fun run(onUpdate: suspend (SpeedTestUiState) -> Unit): SpeedTestResult {
        val startedAt = System.nanoTime()
        var current = SpeedTestUiState(
            stage = "PING",
            message = "Checking latency to the test server…",
            progress = 0.05f
        )
        publish(onUpdate, current)

        val pingSamples = measurePing()
        if (pingSamples.isEmpty()) {
            throw IOException("टेस्ट सर्वर से जवाब नहीं मिला। इंटरनेट कनेक्शन जाँचकर दोबारा कोशिश करें।")
        }
        val sortedPings = pingSamples.sorted()
        val ping = median(sortedPings)
        val jitter = if (pingSamples.size < 2) 0.0 else
            pingSamples.zipWithNext { first, second -> abs(second - first) }.average()

        current = current.copy(
            stage = "DOWNLOAD",
            message = "Measuring real download speed…",
            progress = 0.16f,
            pingMs = ping,
            jitterMs = jitter
        )
        publish(onUpdate, current)

        val download = measureDownload { live, peak, bytes, fraction ->
            current = current.copy(
                stage = "DOWNLOAD",
                message = "Downloading test data from Cloudflare…",
                progress = 0.16f + (fraction * 0.39f),
                liveMbps = live,
                peakMbps = peak,
                pingMs = ping,
                jitterMs = jitter,
                downloadedBytes = bytes
            )
            publish(onUpdate, current)
        }
        current = current.copy(
            stage = "UPLOAD",
            message = "Measuring real upload speed…",
            progress = 0.57f,
            liveMbps = 0.0,
            downloadMbps = download.averageMbps,
            downloadPeakMbps = download.peakMbps,
            downloadedBytes = download.bytes
        )
        publish(onUpdate, current)

        val upload = measureUpload { live, peak, bytes, fraction ->
            current = current.copy(
                stage = "UPLOAD",
                message = "Uploading test data to Cloudflare…",
                progress = 0.57f + (fraction * 0.39f),
                liveMbps = live,
                peakMbps = peak,
                pingMs = ping,
                jitterMs = jitter,
                downloadMbps = download.averageMbps,
                downloadPeakMbps = download.peakMbps,
                downloadedBytes = download.bytes,
                uploadedBytes = bytes
            )
            publish(onUpdate, current)
        }

        val result = SpeedTestResult(
            pingMs = ping,
            jitterMs = jitter,
            downloadMbps = download.averageMbps,
            uploadMbps = upload.averageMbps,
            downloadPeakMbps = download.peakMbps,
            uploadPeakMbps = upload.peakMbps,
            downloadedBytes = download.bytes,
            uploadedBytes = upload.bytes,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000L
        )
        current = current.copy(
            stage = "DONE",
            message = "Speed test completed",
            progress = 1f,
            liveMbps = 0.0,
            pingMs = ping,
            jitterMs = jitter,
            downloadMbps = result.downloadMbps,
            uploadMbps = result.uploadMbps,
            downloadPeakMbps = result.downloadPeakMbps,
            uploadPeakMbps = result.uploadPeakMbps,
            downloadedBytes = result.downloadedBytes,
            uploadedBytes = result.uploadedBytes
        )
        publish(onUpdate, current)
        return result
    }

    private suspend fun publish(
        onUpdate: suspend (SpeedTestUiState) -> Unit,
        state: SpeedTestUiState
    ) {
        withContext(Dispatchers.Main.immediate) { onUpdate(state) }
    }

    private suspend fun measurePing(): List<Double> = withContext(Dispatchers.IO) {
        val values = ArrayList<Double>()
        repeat(5) { index ->
            var connection: HttpURLConnection? = null
            try {
                val url = DOWNLOAD_URL + "?bytes=0&probe=" + index + "_" + System.nanoTime()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val start = System.nanoTime()
                val status = connection.responseCode
                if (status in 200..299) {
                    connection.inputStream.use { it.read() }
                    values.add((System.nanoTime() - start) / 1_000_000.0)
                }
            } catch (_: Exception) {
                // A failed probe is excluded; remaining successful probes are still useful.
            } finally {
                connection?.disconnect()
            }
        }
        values
    }

    private suspend fun measureDownload(
        onSample: suspend (live: Double, peak: Double, bytes: Long, fraction: Float) -> Unit
    ): TransferResult = measureTransfer(
        bytesPerConnection = DOWNLOAD_BYTES,
        expectedBytes = DOWNLOAD_BYTES.toLong() * PARALLEL_CONNECTIONS,
        upload = false,
        onSample = onSample
    )

    private suspend fun measureUpload(
        onSample: suspend (live: Double, peak: Double, bytes: Long, fraction: Float) -> Unit
    ): TransferResult = measureTransfer(
        bytesPerConnection = UPLOAD_BYTES,
        expectedBytes = UPLOAD_BYTES.toLong() * PARALLEL_CONNECTIONS,
        upload = true,
        onSample = onSample
    )

    private suspend fun measureTransfer(
        bytesPerConnection: Int,
        expectedBytes: Long,
        upload: Boolean,
        onSample: suspend (live: Double, peak: Double, bytes: Long, fraction: Float) -> Unit
    ): TransferResult = withContext(Dispatchers.IO) {
        val transferred = AtomicLong(0L)
        val firstError = AtomicReference<String?>(null)
        val startedAt = System.nanoTime()
        var previousBytes = 0L
        var previousAt = startedAt
        var peakMbps = 0.0

        coroutineScope {
            val workers = (0 until PARALLEL_CONNECTIONS).map { workerIndex ->
                async(Dispatchers.IO) {
                    var connection: HttpURLConnection? = null
                    var written = 0L
                    try {
                        val address = if (upload) {
                            UPLOAD_URL
                        } else {
                            DOWNLOAD_URL + "?bytes=" + bytesPerConnection + "&worker=" + workerIndex + "_" + System.nanoTime()
                        }
                        connection = (URL(address).openConnection() as HttpURLConnection).apply {
                            requestMethod = if (upload) "POST" else "GET"
                            connectTimeout = 10_000
                            readTimeout = 15_000
                            useCaches = false
                            setRequestProperty("Cache-Control", "no-cache")
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("User-Agent", "NetPulse-Android-SpeedTest")
                            if (upload) {
                                doOutput = true
                                setRequestProperty("Content-Type", "application/octet-stream")
                                setFixedLengthStreamingMode(bytesPerConnection)
                            }
                        }

                        if (upload) {
                            val chunk = ByteArray(64 * 1024)
                            connection.outputStream.use { output ->
                                var remaining = bytesPerConnection
                                while (remaining > 0) {
                                    currentCoroutineContext().ensureActive()
                                    val count = minOf(remaining, chunk.size)
                                    output.write(chunk, 0, count)
                                    transferred.addAndGet(count.toLong())
                                    written += count.toLong()
                                    remaining -= count
                                }
                                output.flush()
                            }
                            val status = connection.responseCode
                            if (status !in 200..299) {
                                transferred.addAndGet(-written)
                                throw IOException("Upload server returned HTTP " + status)
                            }
                            connection.inputStream.use { input ->
                                while (input.read() >= 0) currentCoroutineContext().ensureActive()
                            }
                        } else {
                            val status = connection.responseCode
                            if (status !in 200..299) throw IOException("Download server returned HTTP " + status)
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    transferred.addAndGet(count.toLong())
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (upload && written > 0L) {
                            // A rejected upload must not be counted as completed transfer data.
                            // The HTTP error branch above already rolls it back.
                        }
                        firstError.compareAndSet(null, error.message ?: "Transfer request failed.")
                    } finally {
                        connection?.disconnect()
                    }
                }
            }

            while (workers.any { it.isActive }) {
                delay(220L)
                val now = System.nanoTime()
                val bytesNow = transferred.get().coerceAtLeast(0L)
                val deltaBytes = (bytesNow - previousBytes).coerceAtLeast(0L)
                val elapsedNanos = (now - previousAt).coerceAtLeast(1L)
                val live = deltaBytes.toDouble() * 8.0 / (elapsedNanos / 1_000_000_000.0) / 1_000_000.0
                peakMbps = max(peakMbps, live)
                val fraction = (bytesNow.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
                onSample(live, peakMbps, bytesNow, fraction)
                previousBytes = bytesNow
                previousAt = now
            }
            workers.forEach { it.await() }
        }

        val bytes = transferred.get().coerceAtLeast(0L)
        val durationNanos = (System.nanoTime() - startedAt).coerceAtLeast(1L)
        if (bytes <= 0L) {
            val detail = firstError.get()
            throw IOException(
                (if (upload) "Upload" else "Download") +
                    " test failed. " + (detail ?: "Test server did not transfer data.")
            )
        }
        val average = bytes.toDouble() * 8.0 / (durationNanos / 1_000_000_000.0) / 1_000_000.0
        peakMbps = max(peakMbps, average)
        onSample(average, peakMbps, bytes, 1f)
        TransferResult(
            averageMbps = average,
            peakMbps = peakMbps,
            bytes = bytes,
            durationMs = durationNanos / 1_000_000L
        )
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }
}
