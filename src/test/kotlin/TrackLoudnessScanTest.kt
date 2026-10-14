package com.alananasss.kittytune.data

import com.alananasss.kittytune.domain.Track
import com.sun.net.httpserver.HttpServer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** No audio device or external network; exercises the decoder used by playback. */
class TrackLoudnessScanTest {
    @Test
    fun `background stream analysis does not contact a stalled server or block decoding`() {
        val requested = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio.mp3") { exchange ->
            requested.countDown()
            try {
                releaseServer.await(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(503, -1)
            } finally {
                exchange.close()
            }
        }
        val decoder = Executors.newSingleThreadExecutor()
        val file = toneFile()
        server.start()
        try {
            TrackLoudnessRepository.scanTrackAsync(
                track(-System.nanoTime()), "http://127.0.0.1:${server.address.port}/audio.mp3"
            )
            assertFalse("Automatic loudness scans must not open network streams",
                requested.await(1, TimeUnit.SECONDS))
            val decoded = decoder.submit<Boolean> {
                val grabber = FFmpegFrameGrabber(file)
                try {
                    grabber.start()
                    grabber.grabSamples()?.samples != null
                } finally {
                    grabber.release()
                }
            }
            assertTrue("Playback decoder must remain available", decoded.get(3, TimeUnit.SECONDS))
        } finally {
            releaseServer.countDown()
            server.stop(0)
            decoder.shutdownNow()
            file.delete()
        }
    }

    @Test
    fun `local paths and file URIs still measure and cache loudness`() {
        val file = toneFile()
        try {
            listOf(file.absolutePath, file.toURI().toString()).forEach { source ->
                val completed = CountDownLatch(1)
                val track = track(-System.nanoTime())
                TrackLoudnessRepository.scanTrackAsync(track, source) { completed.countDown() }
                assertTrue("Local scan must complete: $source", completed.await(8, TimeUnit.SECONDS))
                val info = TrackLoudnessRepository.getLoudness(track.id)
                assertNotNull(info)
                assertTrue("Native analyzer must measure the tone", info!!.integratedLufs in -35f..-5f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `network sources and incomplete files cannot be background scan inputs`() {
        val dir = Files.createTempDirectory("loudness-inputs").toFile()
        try {
            val empty = File(dir, "empty.wav").apply { createNewFile() }
            val partial = File(dir, "123.part").apply { writeBytes(byteArrayOf(1)) }
            listOf(
                "https://example.com/audio.m3u8", "http://example.com/audio.mp3",
                "metrofuse-deezer://track/1", "content://audio/1", "file://server/audio.wav",
                "\\\\server\\share\\audio.wav", "//server/share/audio.wav", "file:%invalid",
                File(dir, "missing.wav").absolutePath, empty.absolutePath, partial.absolutePath,
                dir.absolutePath
            ).forEach { source -> assertNull("Must not scan $source", localLoudnessScanFile(source)) }
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private fun track(id: Long) = Track(id, "Loudness regression", null, 2000L, null)

    private fun toneFile(): File {
        val sampleRate = 44100
        val frames = sampleRate * 2
        val dataSize = frames * 4
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4)
        wav.putShort(4).putShort(16).put("data".toByteArray()).putInt(dataSize)
        repeat(frames) { i ->
            val sample = (sin(2 * PI * 440 * i / sampleRate) * 8000).toInt().toShort()
            wav.putShort(sample).putShort(sample)
        }
        return File.createTempFile("loudness tone ", ".wav").apply { writeBytes(wav.array()) }
    }
}
