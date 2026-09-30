package com.qalens.replay

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

class BoundedArchiveTest {
    private fun root(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("qalens-archive-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe archive was accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pathTraversalAndPrefixSiblingAreRejected() = root { dir ->
        for (path in listOf("../${dir.name}-other/secret", "../../secret", "/tmp/secret", "..\\secret"))
            rejected { BoundedArchive.file(dir, path) }
        assertEquals(File(dir, "frames/a.jpg").canonicalFile, BoundedArchive.file(dir, "frames/a.jpg"))
    }
    @Test fun zipEntryAndTotalExpandedBudgetsAreEnforced() = root { dir ->
        rejected { BoundedArchive.extract(zip("one" to ByteArray(2048)).inputStream(), dir, entryLimit = 1024) }
        rejected { BoundedArchive.extract(zip("one" to ByteArray(800), "two" to ByteArray(800)).inputStream(), dir, totalLimit = 1024) }
    }
    @Test fun nestedGzipIsBoundedAfterInflation() = root { dir ->
        val f = File(dir, "track.json")
        GZIPOutputStream(f.outputStream()).use { it.write(ByteArray(4096)) }
        rejected { BoundedArchive.text(f, 1024) }
    }
    @Test fun normalizedDuplicateAndExcessiveEntriesAreRejected() = root { dir ->
        rejected { BoundedArchive.extract(zip("a" to byteArrayOf(1), "./a" to byteArrayOf(2)).inputStream(), dir) }
        rejected { BoundedArchive.extract(zip("a" to byteArrayOf(1), "b" to byteArrayOf(2)).inputStream(), dir, entriesLimit = 1) }
    }
    @Test fun checksumStreamsBinaryAndDecodesJson() = root { dir ->
        val bytes = "{\"safe\":true}".toByteArray()
        val expected = "%08x".format(CRC32().apply { update(bytes) }.value)
        val binary = File(dir, "video.mp4").apply { writeBytes(bytes) }
        val json = File(dir, "state.json")
        GZIPOutputStream(json.outputStream()).use { it.write(bytes) }
        assertEquals(expected, BoundedArchive.checksum(binary, false))
        assertEquals(expected, BoundedArchive.checksum(json, true))
        assertEquals(String(bytes), BoundedArchive.text(json))
    }
}
