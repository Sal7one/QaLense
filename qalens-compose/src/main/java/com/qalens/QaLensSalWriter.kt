package com.qalens

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages encoded tracks + frame images into a `.sal` (a ZIP). Pure Android file IO — the JSON is
 * produced by the dependency-free encoders in qalens-core ([SalTracks]).
 */
internal object QaLensSalWriter {

    /**
     * @param target       the .sal file to create
     * @param textEntries  entry-path -> file contents (manifest.json, *.json, report.txt)
     * @param framesDir     directory whose files are stored under their [frameRelPaths]
     * @param frameRelPaths zip-relative paths like "frames/000001.jpg" (filename must exist in framesDir parent layout)
     */
    fun write(
        target: File,
        textEntries: Map<String, String>,
        framesDir: File,
        frameRelPaths: List<String>,
        extraFiles: Map<String, File> = emptyMap()
    ): File {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            textEntries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            frameRelPaths.forEach { rel ->
                val file = File(framesDir.parentFile, rel)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(rel))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // Arbitrary binary entries (e.g. the MediaProjection video.mp4).
            extraFiles.forEach { (rel, file) ->
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(rel))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return target
    }
}
