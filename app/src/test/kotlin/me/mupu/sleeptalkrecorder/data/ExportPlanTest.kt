package me.mupu.sleeptalkrecorder.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportPlanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(root: File, path: String, bytes: Int, modified: Long = 1_000_000L) =
        File(root, path).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test
    fun `a first export copies every file`() {
        val source = folder.newFolder("source")
        file(source, "1/0001.wav", 10)
        file(source, "1/features.bin", 4)

        val plan = ExportPlan.of(source, File(folder.root, "missing"))

        assertEquals(ExportPlan(copy = listOf("1/0001.wav", "1/features.bin"), remove = emptyList(), bytesToCopy = 14), plan)
    }

    @Test
    fun `a later export copies only new, grown or newer files and removes those of deleted sleeps`() {
        val source = folder.newFolder("source")
        val target = folder.newFolder("target")
        file(source, "1/0001.wav", 10)
        file(target, "1/0001.wav", 10)
        file(source, "1/0002.wav", 8)
        file(target, "1/0002.wav", 5)
        file(source, "1/features.bin", 4, modified = 2_000_000L)
        file(target, "1/features.bin", 4)
        file(source, "2/0001.wav", 6)
        file(target, "3/0001.wav", 7)

        val plan = ExportPlan.of(source, target)

        assertEquals(listOf("1/0002.wav", "1/features.bin", "2/0001.wav"), plan.copy)
        assertEquals(listOf("3/0001.wav"), plan.remove)
        assertEquals(18L, plan.bytesToCopy)
    }
}
