package com.alananasss.kittytune.audio

import com.sun.net.httpserver.HttpServer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class AudioRecoveryCancellationTest {
    @Test(timeout = 5000)
    fun `replacement clearing stop flag cannot revive obsolete recovery`() {
        checkCancellation(changeDuringResolve = false)
    }

    @Test(timeout = 5000)
    fun `replacement while resolving cannot open the old stream`() {
        checkCancellation(changeDuringResolve = true)
    }

    private fun checkCancellation(changeDuringResolve: Boolean) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/old.mp3") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val engine = AudioEngine()
        val generation = AudioEngine::class.java.getDeclaredField("activeWorkerId").apply { isAccessible = true }
        val stopped = AudioEngine::class.java.getDeclaredField("stopFlag").apply { isAccessible = true }
        try {
            // The old worker owns generation 1. prepare() for generation 2 clears
            // the shared stop flag; simulate that without opening an audio device.
            generation.setLong(engine, if (changeDuringResolve) 1L else 2L)
            stopped.setBoolean(engine, false)
            if (changeDuringResolve) engine.onReResolveUrl = { url ->
                generation.setLong(engine, 2L)
                url
            }
            val recovery = AudioEngine::class.java.getDeclaredMethod(
                "recoverStream", java.lang.Long.TYPE, FFmpegFrameGrabber::class.java,
                String::class.java, Map::class.java, java.lang.Long.TYPE, Integer.TYPE
            ).apply { isAccessible = true }
            val result = recovery.invoke(engine, 1L, null,
                "http://127.0.0.1:${server.address.port}/old.mp3", emptyMap<String, String>(), 0L, 1) as Pair<*, *>
            assertNull(result.first)
            assertNull(result.second)
            assertEquals("An obsolete worker must never reopen its network stream", 0, requests.get())
        } finally {
            engine.release()
            server.stop(0)
        }
    }
}
