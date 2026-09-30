package com.qalens.replay

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Limits apply to expanded bytes, independently at ZIP and nested gzip boundaries. */
internal object BoundedArchive {
    const val TEXT_LIMIT = 16L * 1024 * 1024
    fun file(root: File, name: String): File {
        require(name.isNotBlank() && !File(name).isAbsolute && '\\' !in name) { "Invalid archive path" }
        val result = File(root, name).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator)) { "Archive path escapes session" }
        return result
    }
    fun copy(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            require(count.toLong() <= limit - total) { "Archive size limit exceeded" }
            out.write(buffer, 0, count)
            total += count
        }
    }
    fun extract(input: InputStream, root: File, totalLimit: Long = 512L * 1024 * 1024,
                entryLimit: Long = 256L * 1024 * 1024, entriesLimit: Int = 4096) {
        var total = 0L
        val seen = hashSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = file(root, entry.name)
                require(seen.add(target.path) && seen.size <= entriesLimit) { "Duplicate or excessive archive entries" }
                if (entry.isDirectory) check(target.mkdirs() || target.isDirectory)
                else {
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    target.outputStream().use { total += copy(zip, it, minOf(entryLimit, totalLimit - total)) }
                }
                zip.closeEntry()
            }
        }
    }
    fun decoded(file: File): InputStream {
        val stream = file.inputStream().buffered()
        stream.mark(2)
        val gzip = stream.read() == 0x1f && stream.read() == 0x8b
        stream.reset()
        return try { if (gzip) GZIPInputStream(stream) else stream } catch (error: Exception) { stream.close(); throw error }
    }
    fun text(file: File, limit: Long = TEXT_LIMIT): String = decoded(file).use { input ->
        val out = java.io.ByteArrayOutputStream()
        copy(input, out, limit)
        out.toString("UTF-8")
    }
    fun checksum(file: File, decode: Boolean): String {
        val crc = CRC32()
        (if (decode) decoded(file) else file.inputStream()).use { input ->
            val sink = object : OutputStream() {
                override fun write(value: Int) { crc.update(value) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { crc.update(bytes, offset, count) }
            }
            copy(input, sink, if (decode) TEXT_LIMIT else 256L * 1024 * 1024)
        }
        return "%08x".format(crc.value)
    }
}
