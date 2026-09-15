package org.bayton.gmc.update

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "synthetic APK bytes".toByteArray()
    private fun release(hash: String) = UpdateManifest(3, "1.3", "https://cdn.bayton.org/download/gmc/gmc-1.3.apk", hash)
    private fun repository(code: Int = 200, body: ByteArray = bytes) = UpdateRepository(OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()
    }.build())
    private fun hash(): String = temp.newFile().also { it.writeBytes(bytes) }.let(::sha256)

    @Test fun `verified download yields APK and no partial file`() = runBlocking {
        val directory = temp.newFolder()
        var validated = false
        var received = 0L
        val file = repository().download(release(hash()), directory, { validated = true }) { count, _ -> received = count }
        assertTrue(validated)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), received)
        assertEquals(listOf(file), directory.listFiles()!!.toList())
        assertEquals("apk", file.extension)
    }

    @Test fun `wrong hash and failed identity remove partial files`() {
        for (badHash in listOf(true, false)) {
            val directory = temp.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository().download(release(if (badHash) "0".repeat(64) else hash()), directory,
                        { throw IllegalArgumentException("Wrong signer") }) { _, _ -> }
                }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `redirect and server failures do not produce downloads`() {
        for (code in listOf(302, 404, 500)) {
            val directory = temp.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository(code).download(release(hash()), directory, {}) { _, _ -> } }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `manifest size is bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository(body = ByteArray(65 * 1024)).check() }
        }
    }

    @Test fun `cancelled request cancels call without leaving a file`() = runBlocking {
        val entered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            try {
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(bytes.toResponseBody()).build()
            } finally { returned.countDown() }
        }.build()
        val directory = temp.newFolder()
        val job = launch(Dispatchers.Default) {
            UpdateRepository(client).download(release(hash()), directory, {}) { _, _ -> }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        releaseResponse.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `mid stream cancellation cleans partially written APK`() = runBlocking {
        val wroteChunk = CountDownLatch(1)
        val unblockRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body = object : ResponseBody() {
            private var first = true
            private val stream = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (first) {
                        first = false
                        sink.write(bytes)
                        return bytes.size.toLong()
                    }
                    check(unblockRead.await(5, TimeUnit.SECONDS))
                    return -1
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.countDown() }
            }.buffer()
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = stream
        }
        val client = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body).build()
        }.build()
        val directory = temp.newFolder()
        val job = launch(Dispatchers.Default) {
            UpdateRepository(client).download(release(hash()), directory, {}) { _, _ -> wroteChunk.countDown() }
        }
        assertTrue(wroteChunk.await(5, TimeUnit.SECONDS))
        assertTrue(directory.listFiles()!!.any { it.extension == "part" })
        job.cancelAndJoin()
        unblockRead.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        // Source.close happens before the download's finally; wait for the callback to unwind.
        withTimeout(5000) {
            while (directory.listFiles()!!.isNotEmpty()) delay(10)
        }
        assertTrue(job.isCancelled)
    }
}
