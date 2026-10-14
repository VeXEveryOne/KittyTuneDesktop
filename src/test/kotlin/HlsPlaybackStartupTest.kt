package com.alananasss.kittytune.audio

import com.sun.net.httpserver.HttpServer
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

class HlsPlaybackStartupTest {
    @Test
    fun `opening HLS decodes first fragment without reading later fragments to find stream size`() {
        val fragment = audioFragment()
        val laterRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/track.m3u8") { exchange ->
            val playlist = """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:10,
                first.mp4
                #EXTINF:10,
                later.mp4
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, playlist.size.toLong())
            exchange.responseBody.use { it.write(playlist) }
            exchange.close()
        }
        server.createContext("/first.mp4") { exchange ->
            exchange.sendResponseHeaders(200, fragment.size.toLong())
            exchange.responseBody.use { it.write(fragment) }
            exchange.close()
        }
        server.createContext("/later.mp4") { exchange ->
            laterRequests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val engine = AudioEngine()
        var grabber: FFmpegFrameGrabber? = null
        try {
            val method = AudioEngine::class.java.getDeclaredMethod(
                "createGrabber", String::class.java, Map::class.java, java.lang.Long.TYPE
            ).apply { isAccessible = true }
            grabber = method.invoke(engine, "http://127.0.0.1:${server.address.port}/track.m3u8",
                emptyMap<String, String>(), 0L) as FFmpegFrameGrabber
            grabber.start()
            assertNotNull("First fragment must yield playable samples", grabber.grabSamples()?.samples)
            assertEquals("Startup must not seek to EOF or download subsequent fragments", 0, laterRequests.get())
        } finally {
            grabber?.releaseQuietly()
            engine.release()
            server.stop(0)
        }
    }

    private fun audioFragment(): ByteArray {
        val file = File.createTempFile("hls-startup-", ".mp4")
        val recorder = FFmpegFrameRecorder(file, 2).apply {
            format = "mp4"
            sampleRate = 44100
            audioCodec = AV_CODEC_ID_AAC
            audioBitrate = 160000
            setOption("movflags", "frag_keyframe+empty_moov+default_base_moof")
        }
        try {
            recorder.start()
            val pcm = ShortArray(44100 * 10 * 2) { i ->
                (sin(2 * PI * 440 * (i / 2) / 44100) * 8000).toInt().toShort()
            }
            recorder.recordSamples(44100, 2, ShortBuffer.wrap(pcm))
            recorder.stop()
            return file.readBytes()
        } finally {
            recorder.release()
            file.delete()
        }
    }
}
