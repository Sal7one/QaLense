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
    data class Pairing(val token: String, val port: Int)
    private val mutablePairing = MutableStateFlow<Pairing?>(null)
    val pairing = mutablePairing.asStateFlow()
    @Volatile private var epoch = 0L
    private var listener: ServerSocket? = null
    private var client: Socket? = null
    private var job: Job? = null

    @Synchronized fun stop() {
        QaLensPcPairing.clear()
        epoch++
        job?.cancel(); job = null
        runCatching { client?.close() }; client = null
        runCatching { listener?.close() }; listener = null
        mutableStatus.value = "Stopped"
        mutablePairing.value = null
        QaLensBridgeComponents.clear()
    }

    @Synchronized fun start(token: String, port: Int) {
        require(token.matches(Regex("[A-Za-z0-9_-]{24,128}"))) { "Use a random 24–128 character bridge token" }
        require(port in 1024..65535) { "Bridge port must be 1024–65535" }
        stop()
        if (!QaLens.config.value.enabled) return
        mutablePairing.value = Pairing(token, port)
        val generation = epoch
        mutableStatus.value = "Starting"
        job = scope.launch {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
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
                            val transferring = java.util.concurrent.atomic.AtomicBoolean(false)
                            val deadline = scope.launch {
                                delay(6_000)
                                if (transferring.get()) delay(54_000)
                                runCatching { socket.close() }
                            }
                            try { serve(socket, token, generation) { transferring.set(true) } } finally { deadline.cancel() }
                            synchronized(this@QaLensLocalBridge) { if (client === socket) client = null }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(this@QaLensLocalBridge) {
                    if (generation == epoch) {
                        mutableStatus.value = "Failed to listen on port $port; choose another port or retry"
                        mutablePairing.value = null
                    }
                }
            } finally {
                synchronized(this@QaLensLocalBridge) { if (generation == epoch) listener = null }
            }
        }
    }

    fun startPairing(port: Int) {
        val token = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        start(token, port)
    }

    private suspend fun serve(socket: Socket, token: String, generation: Long, transferring: () -> Unit) {
        var status = 200
        val body = try {
            val request = QaLensBridgeProtocol.read(socket.getInputStream(), token)
            if (request.method == "GET" && request.path.startsWith("/v1/recordings/")) {
                if (generation != epoch || !QaLens.config.value.enabled) throw QaLensBridgeFailure(503, "Bridge stopped")
                val name = request.path.removePrefix("/v1/recordings/")
                if (!name.matches(Regex("(?:session|clip)_[A-Za-z0-9_]+\\.sal"))) throw QaLensBridgeFailure(400, "Invalid recording name")
                val context = QaLens.appContext ?: throw QaLensBridgeFailure(503, "No app context")
                val file = java.io.File(QaLensSessionRecorder.recordingsDir(context), name)
                if (!file.isFile || file.length() > 400L * 1024 * 1024) throw QaLensBridgeFailure(404, "Recording unavailable")
                transferring()
                file.inputStream().use { input ->
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 Bridge\r\nContent-Type: application/octet-stream\r\nContent-Length: ${input.channel.size()}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
                    input.copyTo(output, 128 * 1024); output.flush()
                }
                return
            }
            when {
                request.method == "GET" && request.path == "/v1/recordings" -> {
                    if (generation != epoch || !QaLens.config.value.enabled) throw QaLensBridgeFailure(503, "Bridge stopped")
                    val context = QaLens.appContext ?: throw QaLensBridgeFailure(503, "No app context")
                    mapOf("ok" to true, "recording" to QaLens.state.value.isRecording,
                        "saving" to QaLens.state.value.isSavingRecording,
                        "items" to (QaLensSessionRecorder.recordingsDir(context).listFiles()
                            ?.filter { it.isFile && it.name.matches(Regex("(?:session|clip)_[A-Za-z0-9_]+\\.sal")) }
                            ?.sortedByDescending { it.lastModified() }?.take(30)?.map {
                                mapOf("name" to it.name, "size" to it.length(), "finishedAtMillis" to it.lastModified())
                            } ?: emptyList<Map<String, Any>>()))
                }
                request.method == "GET" && request.path == "/v1/snapshot" -> snapshot(generation)
                request.method == "GET" && request.path == "/v1/selection" -> {
                    requireSession(generation)
                    if (!QaLens.config.value.enableSemanticsReflection) throw QaLensBridgeFailure(403, "Host disabled semantics inspection")
                    mapOf("ok" to true, "selectedId" to QaLens.state.value.selectedNode?.id)
                }
                request.method == "POST" && request.path == "/v1/selectors" -> selectors(readJson(request.body), generation)
                request.method == "POST" && request.path == "/v1/query" -> query(readJson(request.body), generation)
                request.method == "GET" && request.path == "/v1/events" -> observations(generation)
                request.method == "GET" && request.path == "/v1/components/inbox" -> {
                    synchronized(this@QaLensLocalBridge) {
                        requireSession(generation)
                        QaLensBridgeComponents.inbox()
                    }
                }
                request.method == "POST" && request.path == "/v1/components/ack" -> {
                    val input = readJson(request.body)
                    val ids = input.optJSONArray("ids") ?: throw QaLensBridgeFailure(400, "Supply transfer ids")
                    if (ids.length() > 10 || (0 until ids.length()).any { ids.opt(it) !is String || ids.getString(it).length > 128 })
                        throw QaLensBridgeFailure(400, "Supply at most ten string transfer ids <=128 characters")
                    synchronized(this@QaLensLocalBridge) {
                        requireSession(generation)
                        QaLensBridgeComponents.acknowledge((0 until ids.length()).map { ids.getString(it) })
                    }
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

    private fun requireSession(generation: Long) {
        if (generation != epoch || !QaLens.config.value.enabled) throw QaLensBridgeFailure(503, "Bridge stopped")
    }

    private suspend fun <T> onHost(generation: Long, block: (android.app.Activity) -> T): T =
        withTimeoutOrNull(1_500) {
            withContext(Dispatchers.Main.immediate) {
                if (generation != epoch || !QaLens.config.value.enabled) throw QaLensBridgeFailure(503, "Bridge stopped")
                val activity = QaLens.currentActivity?.takeIf { QaLensActivityInstaller.isResumed(it) && !it.isFinishing }
                    ?: throw QaLensBridgeFailure(503, "Return to the app before reading or controlling its live Compose tree")
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
            val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
            val displaySize = android.graphics.Point()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(displaySize)
            mapOf<String, Any?>("ok" to true, "protocol" to 1, "screen" to QaLens.state.value.screen.displayName,
                "route" to QaLens.state.value.screen.route, "capturedAtMillis" to System.currentTimeMillis(),
                "selectedId" to QaLens.state.value.selectedNode?.id,
                "viewport" to mapOf<String, Any?>("width" to activity.window.decorView.width, "height" to activity.window.decorView.height,
                    "originX" to origin[0], "originY" to origin[1]),
                "screenViewport" to mapOf("width" to displaySize.x, "height" to displaySize.y),
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

    private suspend fun selectorTree(generation: Long): Pair<QaLensAutomationInspection.Capture, List<QaLensSelectorNode>> {
        val capture = onHost(generation) { QaLensAutomationInspection.capture(it) }
        val nodes = capture.redacted()
        requireCapture(capture, generation)
        return capture to nodes
    }

    private fun requireCapture(capture: QaLensAutomationInspection.Capture, generation: Long) {
        requireSession(generation)
        if (capture.config != QaLens.config.value) throw QaLensBridgeFailure(409, "Inspection settings changed; refresh and retry")
    }

    private suspend fun selectors(input: JSONObject, generation: Long): Map<String, Any?> {
        val id = input.opt("id") as? String ?: throw QaLensBridgeFailure(400, "Supply a current node id")
        if (id.isBlank() || id.length > 128) throw QaLensBridgeFailure(400, "Supply a current node id")
        val (capture, nodes) = selectorTree(generation)
        if (nodes.none { it.id == id }) throw QaLensBridgeFailure(404, "Element changed; refresh and select it again")
        val result = mapOf("ok" to true, "schema" to "qalens.selectors", "version" to 1, "liveNodeId" to id,
            "suggestions" to QaLensAutomationInspection.suggestions(nodes, id), "xml" to QaLensSelectors.xml(nodes),
            "omittedNodes" to capture.omitted, "coverage" to "QaLens visible Compose XML. XPath is not an Appium/UIAutomator locator. Match counts apply to this snapshot; positions and content can change.")
        requireCapture(capture, generation)
        return result
    }

    private suspend fun query(input: JSONObject, generation: Long): Map<String, Any?> {
        val xpath = input.opt("xpath") as? String ?: throw QaLensBridgeFailure(400, "Supply XPath text")
        try { QaLensSelectors.resolve(emptyList(), xpath) }
            catch (failure: IllegalArgumentException) { throw QaLensBridgeFailure(400, failure.message ?: "Invalid XPath") }
        val (capture, nodes) = selectorTree(generation)
        val matches = try { QaLensSelectors.resolve(nodes, xpath) }
            catch (failure: IllegalArgumentException) { throw QaLensBridgeFailure(400, failure.message ?: "Invalid XPath") }
        requireCapture(capture, generation)
        return mapOf("ok" to true, "count" to matches.size, "omittedNodes" to capture.omitted,
            "nodes" to matches.take(100).map { mapOf("id" to it.id, "attributes" to it.attributes) },
            "omittedMatches" to (matches.size - minOf(matches.size, 100)))
    }

    fun sendComponent(id: String) {
        val generation = epoch
        if (!status.value.startsWith("Listening")) { QaLensBridgeComponents.report("Start PC inspector in Control Room or overlay More tools first"); return }
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
        for (field in listOf("action", "id", "tag", "text", "xpath")) {
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
        val xpath = command.optString("xpath")
        if (listOf(id, tag, xpath).count { it.isNotBlank() } != 1) throw QaLensBridgeFailure(400, "Supply exactly one id, tag or QaLens XPath")
        var xpathCapture: QaLensAutomationInspection.Capture? = null
        val resolvedId = if (xpath.isNotBlank()) {
            try { QaLensSelectors.resolve(emptyList(), xpath) }
                catch (failure: IllegalArgumentException) { throw QaLensBridgeFailure(400, failure.message ?: "Invalid XPath") }
            val (capture, nodes) = selectorTree(generation)
            if (capture.omitted > 0) throw QaLensBridgeFailure(409, "Tree is truncated; XPath uniqueness cannot be established")
            xpathCapture = capture
            val matches = try { QaLensSelectors.resolve(nodes, xpath) }
                catch (failure: IllegalArgumentException) { throw QaLensBridgeFailure(400, failure.message ?: "Invalid XPath") }
            if (matches.isEmpty()) throw QaLensBridgeFailure(404, "XPath matched no visible element")
            if (matches.size != 1) throw QaLensBridgeFailure(409, "XPath is ambiguous (${matches.size} matches); scope it to a parent")
            matches.single().id
        } else id
        val text = command.optString("text")
        if (action == "type" && (!command.has("text") || text.length > 4_096)) throw QaLensBridgeFailure(400, "Type requires text of at most 4096 characters")
        val dx = command.optDouble("dx", 0.0).toFloat()
        val dy = command.optDouble("dy", 0.0).toFloat()
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > 10_000 || kotlin.math.abs(dy) > 10_000)
            throw QaLensBridgeFailure(400, "Scroll deltas must be finite window pixels within ±10000")
        return onHost(generation) { activity ->
            val (visible, raw) = live(activity)
            xpathCapture?.let { before ->
                val current = QaLensAutomationInspection.capture(visible, raw)
                if (current.nodes != before.nodes || current.config != before.config)
                    throw QaLensBridgeFailure(409, "Tree changed while resolving XPath; refresh and verify the target")
            }
            val matches = visible.filter { if (resolvedId.isNotBlank()) it.id == resolvedId else it.testTag == tag }
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
        requireSession(generation) // Cached evidence can be read while Control Room is in front.
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
            is String -> if (!dynamicKeys && field in listOf("id", "parentId", "selectedId", "actions", "action")) item
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
