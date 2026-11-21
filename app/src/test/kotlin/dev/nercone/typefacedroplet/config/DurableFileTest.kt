package dev.nercone.typefacedroplet.config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A durable write replaces the file whole and leaves nothing else behind, whether it succeeds or fails. */
class DurableFileTest {
    @Test
    fun replacesTheContentWholeAndLeavesNoTemporaryFile() {
        val directory = Files.createTempDirectory("durable").toFile()
        try {
            val file = File(directory, "config.json")
            DurableFile.write(file, "first")
            assertEquals("first", file.readText())
            DurableFile.write(file, "second")
            assertEquals("second", file.readText())
            assertEquals(listOf("config.json"), directory.list()!!.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFailedWriteKeepsTheOldContent() {
        val directory = Files.createTempDirectory("durable").toFile()
        try {
            val file = File(directory, "config.json")
            DurableFile.write(file, "kept")
            // A directory in place of the file cannot be replaced by a rename.
            val blocked = File(directory, "blocked").apply { mkdirs(); File(this, "inside").writeText("x") }
            val failed = runCatching { DurableFile.write(blocked, "lost") }.isFailure
            assertTrue(failed)
            assertEquals("kept", file.readText())
            assertFalse(File(directory, ".blocked.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
