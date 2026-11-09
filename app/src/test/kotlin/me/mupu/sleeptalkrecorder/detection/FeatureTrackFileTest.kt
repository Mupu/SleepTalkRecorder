package me.mupu.sleeptalkrecorder.detection

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeatureTrackFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "features.bin") }

    @Test
    fun `a written track reads back hop by hop`() {
        FeatureTrackFile.Writer(file).use { writer ->
            writer.write(ByteArray(8) { it.toByte() })
            writer.write(ByteArray(8) { (10 + it).toByte() })
        }

        val track = FeatureTrackFile.read(file)!!

        assertEquals(2, track.size)
        assertArrayEquals(byteArrayOf(3, 13), track.speech)
        assertArrayEquals(byteArrayOf(7, 17), track.voicing)
    }

    @Test
    fun `a flushed track is readable while it is still written, without a partial hop`() {
        val writer = FeatureTrackFile.Writer(file)
        writer.write(ByteArray(8))
        writer.flush()
        file.appendBytes(ByteArray(3))

        assertEquals(1, FeatureTrackFile.read(file)!!.size)
        writer.close()
    }

    @Test
    fun `a missing file or one of another format is not a track`() {
        assertNull(FeatureTrackFile.read(file))

        file.writeBytes(ByteArray(64))
        assertNull(FeatureTrackFile.read(file))
    }
}
