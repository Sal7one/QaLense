package com.qalens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Explicitly started, memory-only pairing. Networking/JSON/redaction stay off main;
 * live semantics reads/actions are confined to main, as required by Compose.
 */
internal object QaLensLocalBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow("Stopped")
    val status = mutableStatus.asStateFlow()
    @Volatile private var epoch = 0L
    private var listener: ServerSocket? = null
    private var client: Socket? = null
    private var job: Job? = null

    @Synchronized fun stop() {
        epoch++
        job?.cancel(); job = null
        runCatching { client?.close() }; client = null
        runCatching { listener?.close() }; listener = null
        mutableStatus.value = "Stopped"
        QaLensBridgeComponents.clear()
    }

    @Synchronized fun start(token: String, port: Int) {
        require(token.matches(Regex("[A-Za-z0-9_-]{24,128}"))) { "Use a random 24–128 character bridge token" }
        require(port in 1024..65535) { "Bridge port must be 1024–65535" }
        stop()
        if (!QaLens.config.value.enabled) return
        val generation = epoch
        mutableStatus.value = "Starting"
        job = scope.launch {
            try {
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
                    synchronized(this@QaLensLocalBridge) {
                        if (generation != epoch) return@launch
                        listener = server
                        mutableStatus.value = "Listening on 127.0.0.1:$port"
                    }
                    while (isActive && generation == epoch) {
                        server.accept().use { socket ->
                            synchronized(this@QaLensLocalBridge) {
                                if (generation != epoch) return@launch
                                client = socket
                            }
                            socket.soTimeout = 3_000
                            socket.tcpNoDelay = true
                            val deadline = scope.launch { delay(6_000); runCatching { socket.close() } }
                            try { serve(socket, token, generation) } finally { deadline.cancel() }
                            synchronized(this@QaLensLocalBridge) { if (client === socket) client = null }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(this@QaLensLocalBridge) {
                    if (generation == epoch) mutableStatus.value = "Failed to listen on port $port; stop and retry"
                }
            } finally {
                synchronized(this@QaLensLocalBridge) { if (generation == epoch) listener = null }
            }
        }
    }

    private suspend fun serve(socket: Socket, token: String, generation: Long) {
        var status = 200
        val body = try {
            val request = QaLensBridgeProtocol.read(socket.getInputStream(), token)
            when {
                request.method == "GET" && request.path == "/v1/snapshot" -> snapshot(generation)
                request.method == "GET" && request.path == "/v1/events" -> observations(generation)
                request.method == "GET" && request.path == "/v1/components/inbox" -> {
                    onHost(generation) { true }
                    QaLensBridgeComponents.inbox()
                }
                request.method == "POST" && request.path == "/v1/components/ack" -> {
                    val input = readJson(request.body)
                    val ids = input.optJSONArray("ids") ?: throw QaLensBridgeFailure(400, "Supply transfer ids")
                    if (ids.length() > 10 || (0 until ids.length()).any { ids.opt(it) !is String || ids.getString(it).length > 128 })
                        throw QaLensBridgeFailure(400, "Supply at most ten string transfer ids <=128 characters")
                    onHost(generation) { true }
                    QaLensBridgeComponents.acknowledge((0 until ids.length()).map { ids.getString(it) })
                    mapOf("ok" to true)
                }
                request.method == "POST" && request.path == "/v1/component" -> component(readJson(request.body), generation)
                request.method == "POST" && request.path == "/v1/command" -> {
                    val command = try { JSONObject(request.body) } catch (_: Exception) { throw QaLensBridgeFailure(400, "Invalid JSON") }
                    command(command, generation)
                }
                else -> throw QaLensBridgeFailure(404, "Unknown bridge endpoint")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: QaLensBridgeFailure) { status = failure.status; mapOf<String, Any?>("ok" to false, "error" to failure.message) }
        catch (_: java.net.SocketTimeoutException) { status = 408; mapOf<String, Any?>("ok" to false, "error" to "Request timed out") }
        catch (_: Exception) { status = 500; mapOf<String, Any?>("ok" to false, "error" to "Bridge request failed") }
        var bytes = JSONObject(body).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 4 * 1024 * 1024) {
            status = 413
            bytes = JSONObject(mapOf<String, Any?>("ok" to false, "error" to "Tree exceeds 4 MiB response budget")).toString().toByteArray()
        }
        // A non-reading client must not hold an IO worker or delay shutdown indefinitely.
        val expiry = scope.launch { delay(3_000); runCatching { socket.close() } }
        try {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status Bridge\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        } catch (_: Exception) { /* A disconnected PC never affects the host. */ }
        finally { expiry.cancel() }
    }

    private fun readJson(body: String): JSONObject = try { JSONObject(body) }
        catch (_: Exception) { throw QaLensBridgeFailure(400, "Invalid JSON") }

    private suspend fun <T> onHost(generation: Long, block: (android.app.Activity) -> T): T =
        withTimeoutOrNull(1_500) {
            withContext(Dispatchers.Main.immediate) {
                if (generation != epoch || !QaLens.config.value.enabled) throw QaLensBridgeFailure(503, "Bridge stopped")
                val activity = QaLens.currentActivity?.takeIf { QaLensActivityInstaller.isResumed(it) && !it.isFinishing }
                    ?: throw QaLensBridgeFailure(503, "No foreground host Activity")
                block(activity)
            }
        } ?: throw QaLensBridgeFailure(504, "Host is busy; command cancelled before dispatch if still queued")

    private fun live(activity: android.app.Activity): Pair<List<InspectNode>, Map<String, SemanticsNode>> {
        if (!QaLens.config.value.enableSemanticsReflection)
            throw QaLensBridgeFailure(403, "Host disabled semantics inspection")
        val nodes = QaLensActivityInstaller.readVisibleNodes(activity.window.decorView).filterNot { it.hiddenFromReports }
        val raw = QaLensActivityInstaller.rawSemanticsNodes(activity).associateBy(QaLensActivityInstaller::semanticsId)
        return nodes to raw
    }

    private suspend fun snapshot(generation: Long): Map<String, Any?> {
        val result = onHost<Map<String, Any?>>(generation) { activity ->
            val (nodes, raw) = live(activity)
            val included = nodes.take(1_000).map { it.id }.toSet()
            mapOf<String, Any?>("ok" to true, "protocol" to 1, "screen" to QaLens.state.value.screen.displayName,
                "route" to QaLens.state.value.screen.route, "capturedAtMillis" to System.currentTimeMillis(),
                "viewport" to mapOf<String, Any?>("width" to activity.window.decorView.width, "height" to activity.window.decorView.height),
                "omittedNodes" to (nodes.size - included.size),
                "nodes" to nodes.take(1_000).map { node ->
                    val semantic = raw[node.id]
                    val password = QaLensBridgeComponents.password(semantic)
                    var parent = semantic?.parent
                    while (parent != null && QaLensActivityInstaller.semanticsId(parent) !in included) parent = parent.parent
                    mapOf<String, Any?>("id" to node.id, "parentId" to parent?.let(QaLensActivityInstaller::semanticsId),
                        "tag" to node.testTag, "label" to if (password) "[protected]" else node.label,
                        "text" to if (password) emptyList() else node.text.take(8),
                        "description" to if (password) emptyList() else node.contentDescription.take(4), "role" to node.role,
                        "value" to if (password) null else semantic?.config?.getOrNull(SemanticsProperties.EditableText)?.text,
                        "state" to if (password) null else node.stateDescription, "enabled" to node.isEnabled,
                        "bounds" to mapOf<String, Any?>("left" to node.bounds.left, "top" to node.bounds.top, "right" to node.bounds.right, "bottom" to node.bounds.bottom),
                        "actions" to semantic?.let(::actions).orEmpty())
                })
        }
        return sanitize(result)
    }

    private fun actions(node: SemanticsNode): List<String> = buildList {
        if (node.config.contains(SemanticsActions.OnClick)) add("tap")
        if (node.config.contains(SemanticsActions.SetText)) add("type")
        if (node.config.contains(SemanticsActions.ScrollBy)) add("scroll")
    }

    fun sendComponent(id: String) {
        val generation = epoch
        if (!status.value.startsWith("Listening")) { QaLensBridgeComponents.report("Start the bridge in your QA app first"); return }
        QaLensBridgeComponents.report("Reading component…")
        scope.launch {
            try {
                val document = component(JSONObject().put("id", id), generation)
                synchronized(this@QaLensLocalBridge) {
                    if (generation == epoch && QaLens.config.value.enabled) QaLensBridgeComponents.enqueue(document)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: QaLensBridgeFailure) { if (generation == epoch) QaLensBridgeComponents.report(failure.message ?: "Transfer failed") }
            catch (_: Exception) { if (generation == epoch) QaLensBridgeComponents.report("Transfer failed; refresh and retry") }
        }
    }

    private suspend fun component(input: JSONObject, generation: Long): Map<String, Any?> {
        for (field in listOf("id", "tag")) if (input.has(field) && input.opt(field) !is String)
            throw QaLensBridgeFailure(400, "$field must be a string")
        val id = input.optString("id"); val tag = input.optString("tag")
        if (id.isBlank() == tag.isBlank()) throw QaLensBridgeFailure(400, "Supply exactly one exact id or tag")
        val document = onHost(generation) { activity ->
            val (visible, raw) = live(activity)
            val matches = visible.filter { if (id.isNotBlank()) it.id == id else it.testTag == tag }
            if (matches.isEmpty()) throw QaLensBridgeFailure(404, "Component absent, hidden or off-screen")
            if (matches.size != 1) throw QaLensBridgeFailure(409, "Tag is ambiguous; use a live id")
            QaLensBridgeComponents.capture(activity, matches.single(), visible, raw)
        }
        return sanitize(document)
    }

    private suspend fun command(command: JSONObject, generation: Long): Map<String, Any?> {
        for (field in listOf("action", "id", "tag", "text")) {
            if (command.has(field) && command.opt(field) !is String)
                throw QaLensBridgeFailure(400, "$field must be a string")
        }
        for (field in listOf("dx", "dy")) {
            if (command.has(field) && command.opt(field) !is Number)
                throw QaLensBridgeFailure(400, "$field must be a number")
        }
        val action = command.optString("action")
        if (action !in listOf("tap", "type", "scroll", "select")) throw QaLensBridgeFailure(400, "Use tap, type, scroll or select")
        val id = command.optString("id")
        val tag = command.optString("tag")
        if ((id.isBlank()) == (tag.isBlank())) throw QaLensBridgeFailure(400, "Supply exactly one exact id or tag")
        val text = command.optString("text")
        if (action == "type" && (!command.has("text") || text.length > 4_096)) throw QaLensBridgeFailure(400, "Type requires text of at most 4096 characters")
        val dx = command.optDouble("dx", 0.0).toFloat()
        val dy = command.optDouble("dy", 0.0).toFloat()
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > 10_000 || kotlin.math.abs(dy) > 10_000)
            throw QaLensBridgeFailure(400, "Scroll deltas must be finite window pixels within ±10000")
        return onHost(generation) { activity ->
            val (visible, raw) = live(activity)
            val matches = visible.filter { if (id.isNotBlank()) it.id == id else it.testTag == tag }
            if (matches.isEmpty()) throw QaLensBridgeFailure(404, "Target is absent, hidden or off-screen; refresh the tree")
            if (matches.size != 1) throw QaLensBridgeFailure(409, "Tag is ambiguous; use a tree node id")
            val hit = matches.single()
            val node = raw[hit.id] ?: throw QaLensBridgeFailure(409, "Target has no live semantics action")
            if (!hit.isEnabled && action != "select") throw QaLensBridgeFailure(409, "Target is disabled")
            if (action == "select" && QaLens.state.value.isRecording)
                throw QaLensBridgeFailure(409, "Stop recording before opening the inspector")
            val accepted = when (action) {
                "select" -> { QaLens.closePanel(); QaLens.setWatchMode(false); QaLens.setInspectMode(true); QaLens.previewNode(hit); true }
                "tap" -> node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                "type" -> {
                    node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
                    node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) == true
                }
                "scroll" -> node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(dx, dy) == true
                else -> false
            }
            if (!accepted) throw QaLensBridgeFailure(409, "Action is unsupported or was declined")
            QaLens.invalidateInspection()
            mapOf<String, Any?>("ok" to true, "accepted" to true, "id" to hit.id, "action" to action)
        }
    }

    private suspend fun observations(generation: Long): Map<String, Any?> {
        onHost(generation) { true } // Require a live session, without recomputing reports/providers.
        val state = QaLens.state.value
        return sanitize(mapOf<String, Any?>("ok" to true, "coverage" to "Bounded dashboard observations, not a complete recording",
            "events" to state.events.takeLast(100).map { mapOf<String, Any?>("time" to it.timestampMillis, "type" to it.type.name, "tag" to it.tag, "message" to it.message) },
            "network" to state.networkEvents.takeLast(100).map { mapOf<String, Any?>("method" to it.method, "url" to it.url, "status" to it.status, "durationMs" to it.latencyMs) },
            "omittedEvents" to (state.events.size - minOf(state.events.size, 100)),
            "omittedNetwork" to (state.networkEvents.size - minOf(state.networkEvents.size, 100)),
            "omittedDataSources" to (state.dataSources.size - minOf(state.dataSources.size, 20)),
            "dataSources" to state.dataSources.entries.take(20).associate { (name, values) -> name to values.entries.take(100).associate { it.key to it.value } }))
    }

    @Suppress("UNCHECKED_CAST")
    private fun sanitize(value: Map<String, Any?>): Map<String, Any?> {
        val config = QaLens.config.value
        fun clean(item: Any?, field: String = "", dynamicKeys: Boolean = false): Any? = when (item) {
            is String -> if (!dynamicKeys && field in listOf("id", "parentId", "actions", "action")) item
                else config.redact(item).take(2_048)
            is Map<*, *> -> item.entries.associate {
                val key = it.key.toString()
                (if (dynamicKeys) config.redact(key).take(128) else key) to clean(it.value, key, dynamicKeys || key == "dataSources")
            }
            is List<*> -> item.map { clean(it, field, dynamicKeys) }
            else -> item
        }
        return clean(value) as Map<String, Any?>
    }
}
