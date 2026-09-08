package com.qalens

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class QaLensOkHttpInterceptorTest {
    private fun interceptor(config: () -> QaLensConfig = { QaLensConfig() }, sink: (NetworkEvent) -> Unit) =
        QaLensOkHttpInterceptor(config, sink, { null })

    @Test fun responseAndRequestBytesSurviveBodyObservation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("response-body").setHeader("Content-Type", "text/plain"))
            val events = mutableListOf<NetworkEvent>()
            val client = OkHttpClient.Builder().addInterceptor(interceptor({ QaLensConfig(captureNetworkBodies = true) }, events::add)).build()
            client.newCall(Request.Builder().url(server.url("/test")).post("request-body".toRequestBody("text/plain".toMediaType())).build()).execute().use {
                assertEquals("response-body", it.body!!.string())
            }
            assertEquals("request-body", server.takeRequest().body.readUtf8())
            assertEquals("request-body", events.single().requestBodyPreview)
            assertEquals("response-body", events.single().responseBodyPreview)
        }
    }

    @Test fun duplicateInstallationCapturesOnceButReusedRequestStillCapturesNewCall() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
            val events = mutableListOf<NetworkEvent>()
            val observer = interceptor(sink = events::add)
            val client = OkHttpClient.Builder().addInterceptor(observer).addInterceptor(observer).addNetworkInterceptor(observer).build()
            val request = Request.Builder().url(server.url("/test")).build()
            val returned = client.newCall(request).execute().use { it.request }
            client.newCall(returned).execute().close()
            assertEquals(2, events.size)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun legacyChuckerFlagNeverSilencesFallbackInterceptor() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val events = mutableListOf<NetworkEvent>()
            val client = OkHttpClient.Builder().addInterceptor(interceptor({ QaLensConfig(networkFromChucker = true) }, events::add)).build()
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            assertEquals(503, events.single().status)
        }
    }

    @Test fun disabledCaptureCanBeEnabledAfterClientConstruction() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
            var config = QaLensConfig(enabled = false)
            val events = mutableListOf<NetworkEvent>()
            val client = OkHttpClient.Builder().addInterceptor(interceptor({ config }, events::add)).build()
            val request = Request.Builder().url(server.url("/")).build()
            client.newCall(request).execute().close()
            config = QaLensConfig(captureNetwork = false)
            client.newCall(request).execute().close()
            config = QaLensConfig()
            client.newCall(request).execute().close()
            assertEquals(1, events.size)
            assertNull(events.single().responseBodyPreview)
        }
    }

    @Test fun failingObserverCannotBreakSuccessfulResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("success"))
            val client = OkHttpClient.Builder().addInterceptor(interceptor { error("observer failed") }).build()
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals("success", it.body!!.string())
            }
        }
    }

    @Test fun originalTransportExceptionIsRethrownEvenIfObserverFails() {
        val expected = IOException("transport failure")
        val client = OkHttpClient.Builder().addInterceptor(interceptor { error("observer failed") })
            .addInterceptor { throw expected }.build()
        try {
            client.newCall(Request.Builder().url("http://example.invalid").build()).execute()
            fail("Expected failure")
        } catch (actual: IOException) { assertSame(expected, actual) }
    }

    @Test fun redirectProducesOneCompletedCallWithFinalStatus() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
            val events = mutableListOf<NetworkEvent>()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(sink = events::add)).build()
            client.newCall(Request.Builder().url(server.url("/start")).build()).execute().close()
            assertEquals(201, events.single().status)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun concurrentCallsEachProduceOneEvent() {
        MockWebServer().use { server ->
            repeat(20) { server.enqueue(MockResponse().setBody("ok")) }
            val events = CopyOnWriteArrayList<NetworkEvent>()
            val client = OkHttpClient.Builder().addInterceptor(interceptor(sink = events::add)).build()
            val threads = List(4) { n -> Thread {
                repeat(5) { client.newCall(Request.Builder().url(server.url("/$n/$it")).build()).execute().close() }
            } }
            threads.forEach(Thread::start); threads.forEach(Thread::join)
            assertEquals(20, events.size)
            assertEquals(20, events.map { it.url }.toSet().size)
        }
    }
}
