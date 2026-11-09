package me.mupu.sleeptalkrecorder.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelInstallerTest {

    private val directory: File = Files.createTempDirectory("models").toFile()

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private val encoder = bytes(5_000, seed = 1)
    private val tokens = bytes(300, seed = 2)
    private val files = listOf(file("encoder.onnx", encoder), file("tokens.txt", tokens))

    /** Serves [content] by URL; remembers every requested offset. */
    private class FakeServer(
        private val content: Map<String, ByteArray>,
        private val honoursRange: Boolean = true,
        /** Ends every response after this many bytes with an I/O error, as a dropped connection does. */
        private val dropAfter: Int? = null,
    ) : ByteSource {
        val requests = mutableListOf<Pair<String, Long>>()

        override fun open(url: String, offset: Long): ByteSource.Body {
            requests += url to offset
            val bytes = content.getValue(url)
            val start = if (honoursRange) offset else 0L
            val body = bytes.copyOfRange(start.toInt(), bytes.size)
            val stream: InputStream = if (dropAfter == null) {
                ByteArrayInputStream(body)
            } else {
                DroppingStream(body, dropAfter)
            }
            return ByteSource.Body(start, stream)
        }
    }

    private class DroppingStream(private val body: ByteArray, private val limit: Int) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= limit) throw IOException("connection reset")
            return if (position < body.size) body[position++].toInt() and 0xFF else -1
        }
    }

    private fun server(vararg overrides: Pair<String, ByteArray>, honoursRange: Boolean = true, dropAfter: Int? = null) =
        FakeServer(
            mapOf(url("encoder.onnx") to encoder, url("tokens.txt") to tokens) + overrides,
            honoursRange,
            dropAfter,
        )

    private fun installer(source: ByteSource, freeBytes: Long = Long.MAX_VALUE) =
        ModelInstaller(directory, source, freeBytes = { freeBytes })

    @Test
    fun `installs every file and reports progress up to the total`() = runTest {
        val progress = mutableListOf<Long>()

        installer(server()).install(MODEL, files) { done, total ->
            assertEquals(5_300L, total)
            progress += done
        }

        assertTrue(installer(server()).isInstalled(MODEL, files))
        assertArrayEquals(encoder, File(directory, "$MODEL/encoder.onnx").readBytes())
        assertArrayEquals(tokens, File(directory, "$MODEL/tokens.txt").readBytes())
        assertEquals(5_300L, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `an interrupted download keeps what it got, and the next attempt continues from there`() = runTest {
        try {
            installer(server(dropAfter = 2_000)).install(MODEL, files) { _, _ -> }
            fail("The dropped connection must end the attempt")
        } catch (_: IOException) {
        }
        assertFalse(installer(server()).isInstalled(MODEL, files))

        val resumed = server()
        installer(resumed).install(MODEL, files) { _, _ -> }

        assertEquals(url("encoder.onnx") to 2_000L, resumed.requests.first())
        assertArrayEquals(encoder, File(directory, "$MODEL/encoder.onnx").readBytes())
    }

    @Test
    fun `a server that cannot resume makes the file start over`() = runTest {
        partial("encoder.onnx", encoder.copyOf(1_000))

        installer(server(honoursRange = false)).install(MODEL, files) { _, _ -> }

        assertArrayEquals(encoder, File(directory, "$MODEL/encoder.onnx").readBytes())
    }

    @Test
    fun `a partial file longer than the real one starts over`() = runTest {
        partial("tokens.txt", bytes(400, seed = 9))

        val server = server()
        installer(server).install(MODEL, files) { _, _ -> }

        assertEquals(url("tokens.txt") to 0L, server.requests.last())
        assertArrayEquals(tokens, File(directory, "$MODEL/tokens.txt").readBytes())
    }

    @Test
    fun `a complete partial file is only checked, not downloaded again`() = runTest {
        partial("tokens.txt", tokens)

        val server = server()
        installer(server).install(MODEL, files) { _, _ -> }

        assertEquals(listOf(url("encoder.onnx") to 0L), server.requests)
        assertTrue(installer(server).isInstalled(MODEL, files))
    }

    @Test
    fun `a file with the wrong checksum is thrown away and fails the install`() = runTest {
        val damaged = encoder.copyOf().also { it[42] = (it[42] + 1).toByte() }

        try {
            installer(server(url("encoder.onnx") to damaged)).install(MODEL, files) { _, _ -> }
            fail("A damaged file must not be installed")
        } catch (failure: DamagedDownloadException) {
            assertEquals("encoder.onnx", failure.fileName)
        }

        assertFalse(installer(server()).isInstalled(MODEL, files))
        assertEquals(emptyList<String>(), File(directory, MODEL).list()!!.toList())
    }

    @Test
    fun `an install that does not fit into the free space fails before downloading`() = runTest {
        partial("encoder.onnx", encoder.copyOf(1_000))
        val server = server()

        try {
            installer(server, freeBytes = 4_000).install(MODEL, files) { _, _ -> }
            fail("The install must not start without space")
        } catch (failure: NotEnoughSpaceException) {
            assertEquals(4_300L + ModelInstaller.SPACE_RESERVE_BYTES, failure.neededBytes)
        }
        assertEquals(emptyList<Pair<String, Long>>(), server.requests)
    }

    @Test
    fun `deleting removes the installed and the partial files`() = runTest {
        installer(server()).install(MODEL, files) { _, _ -> }
        partial("extra.part", bytes(10, seed = 3))

        installer(server()).delete(MODEL)

        assertFalse(File(directory, MODEL).exists())
        assertFalse(installer(server()).isInstalled(MODEL, files))
    }

    private fun partial(name: String, content: ByteArray) {
        File(directory, MODEL).mkdirs()
        val target = if (name.endsWith(".part")) name else "$name.part"
        File(directory, "$MODEL/$target").writeBytes(content)
    }

    private companion object {
        const val MODEL = "test-model"

        fun url(name: String) = "https://example.org/$name"

        fun file(name: String, content: ByteArray) = ModelFile(name, url(name), content.size.toLong(), sha256(content))

        fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }

        fun sha256(content: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }
}
