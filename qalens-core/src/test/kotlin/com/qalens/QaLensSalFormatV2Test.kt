package com.qalens

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaLensSalFormatV2Test {

    private val config = QaLensConfig()

    @Test
    fun crc32HexKnownConstants() {
        assertEquals("00000000", SalTracks.crc32Hex(ByteArray(0)))
        assertEquals("cbf43926", SalTracks.crc32Hex("123456789".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun gzipRoundTrips() {
        val text = "{\"a\":1,\"b\":\"hello\"}"
        val gz = SalTracks.gzip(text)
        val decoded = GZIPInputStream(gz.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(text, decoded)
    }

    @Test
    fun tracksRoundTripThroughGzip() {
        val timeline = SalTracks.timeline(
            listOf(TimelineEvent(1000L, TimelineKind.ACTION, "Tapped Recharge")),
            config
        )
        val network = SalTracks.network(
            listOf(NetworkEvent(method = "GET", url = "https://api.example.com/x", status = 200)),
            config
        )
        val logs = SalTracks.logs(
            listOf(QaEvent(type = QaEventType.LOG, message = "hello")),
            config
        )
        for (track in listOf(timeline, network, logs)) {
            val gz = SalTracks.gzip(track)
            val decoded = GZIPInputStream(gz.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(track, decoded)
        }
    }

    @Test
    fun manifestFilesV1StringsV2Objects() {
        val m = SalManifest(
            createdAtMillis = 1000, appName = "App", appVersion = "1.0", buildVariant = "debug",
            environment = "staging", gitSha = "abc1234", device = "Pixel", androidVersion = "14",
            startMillis = 1000, endMillis = 6000, fps = 2,
            frameIndex = mapOf(1000L to "frames/000001.jpg"),
            files = listOf("manifest.json", "frames/000001.jpg"),
            counts = mapOf("frames" to 1, "network" to 0)
        )

        // v1: files is an array of strings.
        val v1 = SalTracks.manifest(m)
        assertTrue("\"formatVersion\":1" in v1)
        assertTrue("\"files\":[\"manifest.json\",\"frames/000001.jpg\"]" in v1)

        // v2: files is an array of {name, crc32, compressed} objects.
        val entries = listOf(
            SalFileEntry("summary.json", SalTracks.crc32Hex("{}".toByteArray(Charsets.UTF_8)), true),
            SalFileEntry("frames/000001.jpg", SalTracks.crc32Hex(byteArrayOf(1, 2, 3)), false)
        )
        val v2 = SalTracks.manifest(m.copy(formatVersion = 2), entries)
        assertTrue("\"formatVersion\":2" in v2)
        assertTrue("\"files\":[{\"name\":\"summary.json\",\"crc32\":\"" in v2)
        assertTrue("\"compressed\":true" in v2)
        assertTrue("\"compressed\":false" in v2)
    }
}
