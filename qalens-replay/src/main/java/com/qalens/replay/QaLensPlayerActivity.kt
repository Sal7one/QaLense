package com.qalens.replay

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0E1116)
private val Surface = Color(0xFF1A1F27)
private val TextMain = Color(0xFFE6E6E6)
private val Muted = Color(0xFF93A1B0)
private val Accent = Color(0xFF60A5FA)
private val Err = Color(0xFFF87171)
private val Green = Color(0xFF4ADE80)

/** Standalone player for `.sal` session recordings. Debug/QA tool — opened from the Control Room or a shared file. */
class QaLensPlayerActivity : ComponentActivity() {
    // singleTask: a later VIEW intent arrives via onNewIntent instead of a fresh activity.
    private val viewUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewUri.value = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null
        setContent {
            val uri by viewUri
            PlayerRoot(uri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) viewUri.value = intent.data
    }
}

@Composable
private fun PlayerRoot(initialUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PlayerSession?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // True when the current session was loaded from the launch/VIEW intent (Control Room ▶ Play or
    // a shared file) rather than the in-app picker. Such sessions Close by FINISHING the activity
    // — returning to whoever opened it — instead of dropping the user on the empty picker.
    var loadedFromIntent by remember { mutableStateOf(false) }

    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(session) {
        val loaded = session
        onDispose { loaded?.let(QaLensSalReader::release) }
    }

    fun load(uri: Uri, fromIntent: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            var loaded: PlayerSession? = null
            loading = true; error = null
            try {
                session = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { QaLensSalReader.read(context, it) }
                        ?.also { loaded = it } ?: error("Could not open file")
                }
                loadedFromIntent = fromIntent
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                loaded?.let(QaLensSalReader::release)
                throw cancelled
            } catch (e: Exception) {
                loaded?.let(QaLensSalReader::release)
                error = e.message ?: "Failed to read .sal"
            }
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it, fromIntent = false) }
    }

    LaunchedEffect(initialUri) { initialUri?.let { load(it, fromIntent = true) } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        val current = session
        when {
            current != null -> androidx.compose.runtime.key(current.rootDir) { PlayerScreen(current) {
                // Came from the Control Room / a shared file → finish so we return there.
                // Opened a file via the in-app picker → back to the picker landing.
                if (loadedFromIntent) context.findActivity()?.finish() else session = null
            } }
            else -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("QaLens Player", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("Open a .sal session recording to replay it.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.background(Accent, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable { picker.launch(arrayOf("*/*")) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) { Text("Open .sal", color = Color(0xFF0E1116), fontWeight = FontWeight.SemiBold) }
                if (loading) { Spacer(Modifier.height(16.dp)); Text("Loading…", color = Muted) }
                error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = Err, fontSize = 12.sp) }
            }
        }
    }
}

private enum class Track(val label: String) {
    SUMMARY("Summary"), TIMELINE("Timeline"), NETWORK("Network"), LOGS("Logs"), STATE("State"), INSIGHTS("Insights")
}

private fun scoreColor(score: Int): Color = when {
    score >= 85 -> Green
    score >= 70 -> Color(0xFFFBBF24)
    score >= 50 -> Color(0xFFFB923C)
    else -> Err
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun PlayerScreen(session: PlayerSession, onClose: () -> Unit) {
    val clock = remember(session) {
        ReplayTimeline(session.startMs, session.endMs, session.videoFile != null, session.videoStartMs)
    }
    var playhead by remember { mutableLongStateOf(session.startMs) }
    var playing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var followEvents by remember { mutableStateOf(true) }
    var videoDuration by remember { mutableStateOf<Long?>(null) }
    var videoBuffering by remember { mutableStateOf(false) }
    var videoError by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf(Track.TIMELINE) }
    val insights = remember(session) { InsightsUiState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val timelineList = rememberLazyListState()
    val networkList = rememberLazyListState()
    val logsList = rememberLazyListState()

    // Optional MediaProjection video track — when present, ExoPlayer is the playback clock.
    val exo = remember(session.videoFile) {
        session.videoFile?.let { f ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
                setSeekParameters(SeekParameters.EXACT)
                prepare()
            }
        }
    }
    val videoReleased = remember(exo) { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                videoDuration = player.duration.takeIf { it > 0 }
                videoBuffering = player.playbackState == Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: PlaybackException) {
                playing = false
                videoError = "Video could not be played (${error.errorCodeName}). You can still replay events, or reopen the recording to retry video."
            }
        }
        exo?.addListener(listener)
        onDispose { videoReleased.set(true); exo?.removeListener(listener); exo?.release() }
    }
    DisposableEffect(lifecycleOwner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                playing = false; scrubbing = false; resumeAfterScrub = false; exo?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun seekMedia() { if (videoError == null) clock.videoPosition(playhead, videoDuration)?.let { exo?.seekTo(it) } }
    fun seekTo(ts: Long) {
        playing = false; scrubbing = false; resumeAfterScrub = false
        exo?.pause()
        playhead = clock.clamp(ts)
        followEvents = true
        seekMedia()
    }
    fun togglePlayback() {
        if (playing) { playing = false; exo?.pause() }
        else {
            if (playhead >= session.endMs) seekTo(session.startMs)
            followEvents = true
            playing = true
        }
    }
    fun scrub(ts: Long) {
        if (!scrubbing) { resumeAfterScrub = playing; playing = false; exo?.pause() }
        scrubbing = true
        followEvents = true
        playhead = clock.clamp(ts)
    }
    fun finishScrub() {
        seekMedia()
        scrubbing = false
        playing = resumeAfterScrub && playhead < session.endMs
        resumeAfterScrub = false
    }
    BackHandler(enabled = fullscreen) { if (scrubbing) finishScrub(); fullscreen = false }

    // Once media metadata arrives, align the initial/queued seek (including legacy end alignment).
    LaunchedEffect(videoDuration) { if (videoDuration != null) seekMedia() }
    // Coalesce drag previews; the final seek is immediate and preserves pre-drag Play/Pause state.
    LaunchedEffect(scrubbing, playhead) { if (scrubbing) { delay(80); seekMedia() } }
    LaunchedEffect(playing, exo) {
        if (!playing) return@LaunchedEffect
        var previousTick = SystemClock.elapsedRealtime()
        try {
            while (playing) {
                val now = SystemClock.elapsedRealtime()
                playhead = clock.advance(
                    playhead, now - previousTick, videoDuration, exo?.currentPosition ?: 0,
                    exo?.playbackState == Player.STATE_READY,
                    exo?.playbackState == Player.STATE_ENDED, videoFailed = videoError != null
                )
                previousTick = now
                exo?.playWhenReady = videoError == null && clock.videoWindow(videoDuration)?.contains(playhead) == true
                if (playhead >= session.endMs) { playing = false; break }
                delay(50)
            }
        } finally {
            // Composition disposal can release the player before this cancelled loop unwinds.
            if (!videoReleased.get()) exo?.pause()
        }
    }

    val navigationEvents = when (track) {
        Track.TIMELINE -> session.timeline
        Track.NETWORK -> session.network
        Track.LOGS -> session.logs
        else -> session.allEvents
    }
    fun stepNext() {
        navigationEvents.getOrNull(navigationEvents.latestIndexAt(playhead) { it.ts } + 1)?.let { seekTo(it.ts) }
    }
    fun stepPrev() {
        navigationEvents.getOrNull(navigationEvents.firstIndexAtOrAfter(playhead) { it.ts } - 1)?.let { seekTo(it.ts) }
    }
    fun firstError() { session.allEvents.firstOrNull { it.isError }?.let { failure ->
        track = when {
            session.timeline.any { it === failure } -> Track.TIMELINE
            session.network.any { it === failure } -> Track.NETWORK
            else -> Track.LOGS
        }
        seekTo(failure.ts)
    } }
    val videoWindow = clock.videoWindow(videoDuration)

    BackHandler(enabled = track == Track.INSIGHTS && !fullscreen) { track = Track.TIMELINE }
    if (track == Track.INSIGHTS && !fullscreen) {
        val runtime = remember(session, playhead, insights.runtimeRevision) {
            InsightsRuntime(System.currentTimeMillis(), playhead - session.startMs, linkedMapOf(
                "mediaKind" to when { session.videoFile != null -> "video"; session.frames.isNotEmpty() -> "frames"; else -> "none" },
                "playing" to playing, "scrubbing" to scrubbing, "followEvents" to followEvents,
                "videoPositionMs" to exo?.currentPosition, "videoDurationMs" to videoDuration,
                "videoStartMs" to session.videoStartMs?.minus(session.startMs),
                "videoWindowStartMs" to videoWindow?.startMs?.minus(session.startMs),
                "videoWindowEndMs" to videoWindow?.endMs?.minus(session.startMs),
                "decoderPlaybackState" to exo?.playbackState, "decoderPlaying" to exo?.isPlaying,
                "buffering" to videoBuffering, "lastPlayerError" to videoError,
                "savedFrameCount" to session.frames.size,
                "currentSavedFrameMs" to session.frameAt(playhead)?.ts?.minus(session.startMs),
                "recordingWarnings" to session.recordingWarnings.take(5).joinToString(" ").take(600)
            ))
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Recording", color = Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable(role = Role.Button) { track = Track.TIMELINE }.padding(vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(session.appLabel, color = Muted, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.weight(2f), overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f)) {
                InsightsPane(session, playhead - session.startMs, runtime, insights) { relative ->
                    seekTo(session.startMs + relative); track = Track.TIMELINE
                }
            }
        }
        return
    }

    if (fullscreen) {
        // ── Theatre mode: media fills the screen; slim transport overlaid at the bottom ──
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MediaViewport(session, exo, playhead, videoWindow, videoError, Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xD90E1116))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("${fmtFine(playhead - session.startMs)} / ${fmt(session.durationMs)}",
                    color = Muted, fontSize = 11.sp,
                    modifier = Modifier.semantics { contentDescription = "Playback time" })
                Slider(
                    value = (playhead - session.startMs).toFloat().coerceIn(0f, session.durationMs.toFloat()),
                    onValueChange = { scrub(session.startMs + it.toLong()) },
                    onValueChangeFinished = { finishScrub() },
                    valueRange = 0f..session.durationMs.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Recording position" }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ctrl("⏮", description = "Previous event") { stepPrev() }
                    Ctrl(if (playing) "⏸" else "▶", description = if (playing) "Pause recording" else "Play recording") { togglePlayback() }
                    Ctrl("⏭", description = "Next event") { stepNext() }
                    Spacer(Modifier.weight(1f))
                    Ctrl("⛶ exit", Accent, "Exit fullscreen") { if (scrubbing) finishScrub(); fullscreen = false }
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(12.dp)
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Close", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClose))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.appLabel, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (exo != null) "HD video · ${session.durationMs / 1000}s session"
                    else "${session.frames.size} captured frames · ${session.durationMs / 1000}s",
                    color = Muted, fontSize = 10.sp)
            }
            Text("${fmtFine(playhead - session.startMs)} / ${fmt(session.durationMs)}", color = Muted, fontSize = 11.sp,
                modifier = Modifier.semantics { contentDescription = "Playback time" })
            Text("Insights", color = Accent, fontSize = 12.sp,
                modifier = Modifier.clickable(role = Role.Button) {
                    playing = false; exo?.pause(); track = Track.INSIGHTS
                }.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
        }

        Spacer(Modifier.height(8.dp))

        if (session.recordingWarnings.isNotEmpty()) {
            Text("Partial recording — " + session.recordingWarnings.joinToString(" "),
                color = Color(0xFFFFD180), fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp)
                    .verticalScroll(rememberScrollState()).padding(bottom = 8.dp))
        }

        // Viewport — video (ExoPlayer) when present, else the nearest captured frame.
        // Flexible height (not a fixed aspect ratio): it shares the screen with the track pane
        // below, so Summary/Timeline/Logs always stay visible on phones. Media letterboxes inside.
        // Tap the media (or the ⛶ button) for fullscreen theatre mode.
        MediaViewport(
            session, exo, playhead, videoWindow, videoError,
            Modifier.fillMaxWidth()
                .weight(1.15f)
                .background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable { fullscreen = true }
        )

        Spacer(Modifier.height(6.dp))

        // Scrubber
        Slider(
            value = (playhead - session.startMs).toFloat().coerceIn(0f, session.durationMs.toFloat()),
            onValueChange = { scrub(session.startMs + it.toLong()) },
            onValueChangeFinished = { finishScrub() },
            valueRange = 0f..session.durationMs.toFloat(),
            modifier = Modifier.semantics { contentDescription = "Recording position" }
        )

        // Controls
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Ctrl("⏮", description = "Previous event") { stepPrev() }
            Ctrl(if (playing) "⏸" else "▶", description = if (playing) "Pause recording" else "Play recording") { togglePlayback() }
            Ctrl("⏭", description = "Next event") { stepNext() }
            Ctrl("⛶", description = "Fullscreen") { fullscreen = true }
            Ctrl("⚠ error", Err, "First error") { firstError() }
        }

        if (exo != null && videoError == null && videoBuffering) {
            Text("Buffering video…", color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))

        // Track tabs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Track.entries.forEach { t ->
                val active = t == track
                Text(t.label, color = if (active) Accent else Muted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 12.sp,
                    modifier = Modifier.semantics { selected = active }
                        .clickable(role = Role.Tab) {
                            if (t == Track.INSIGHTS) { playing = false; exo?.pause() }
                            track = t; followEvents = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        // Synced pane
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (track) {
                Track.SUMMARY -> SummaryPane(session.summary)
                Track.STATE -> StatePane(session.stateAt(playhead))
                else -> {
                    val items = when (track) {
                        Track.TIMELINE -> session.timeline
                        Track.NETWORK -> session.network
                        Track.LOGS -> session.logs
                        else -> emptyList()
                    }
                    val list = when (track) {
                        Track.NETWORK -> networkList
                        Track.LOGS -> logsList
                        else -> timelineList
                    }
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (followEvents) "Following playback" else "Browsing events", color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (followEvents) "Follow on" else "Follow playback", color = Accent, fontSize = 12.sp,
                                modifier = Modifier.semantics {
                                    contentDescription = "Follow events"
                                    stateDescription = if (followEvents) "On" else "Off"
                                }.clickable(role = Role.Button) { followEvents = !followEvents }
                                    .padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                        EventPane(items, playhead, session.startMs, list, followEvents,
                            onBrowse = { followEvents = false }, onSeek = { seekTo(it) },
                            modifier = Modifier.weight(1f), label = track.label)
                    }
                }
            }
        }
    }
}

/** The media surface: ExoPlayer video when the .sal has one, else the nearest captured frame. */
@Composable
private fun MediaViewport(
    session: PlayerSession,
    exo: ExoPlayer?,
    playhead: Long,
    videoWindow: ReplayTimeline.VideoWindow?,
    videoError: String?,
    modifier: Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (exo != null) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                modifier = Modifier.fillMaxSize(),
                onReset = null,
                onRelease = { it.player = null },
                update = { if (it.player !== exo) it.player = exo }
            )
            val message = when {
                videoError != null -> videoError
                videoWindow == null -> "Preparing video…"
                playhead < videoWindow.startMs -> "No video at this time.\nVideo starts at ${fmtFine(videoWindow.startMs - session.startMs)}."
                playhead >= videoWindow.endMs -> "Video ended at ${fmtFine(videoWindow.endMs - session.startMs)}.\nThe session's events continue."
                else -> null
            }
            if (message != null) Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(message, color = if (videoError != null) Err else Muted, fontSize = 12.sp)
            }
        } else {
            val frame = session.frameAt(playhead)
            var bitmap by remember(frame?.file?.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
            var decoding by remember(frame?.file?.path) { mutableStateOf(frame != null) }
            LaunchedEffect(frame?.file?.path) {
                bitmap = withContext(Dispatchers.IO) {
                    frame?.file?.let { file -> runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull() }
                }
                decoding = false
            }
            val displayed = bitmap
            if (displayed != null) {
                Image(displayed.asImageBitmap(), contentDescription = "Recorded frame at ${fmtFine((frame?.ts ?: session.startMs) - session.startMs)}", modifier = Modifier.fillMaxSize())
            } else {
                Text(when {
                    frame == null -> "No frame captured at this time."
                    decoding -> "Loading frame…"
                    else -> "Frame could not be decoded."
                }, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EventPane(
    items: List<PItem>, playhead: Long, startMs: Long, listState: LazyListState, follow: Boolean,
    onBrowse: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier, label: String
) {
    val currentIndex = items.latestIndexAt(playhead) { it.ts }
    val browse by rememberUpdatedState(onBrowse)
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) browse()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(currentIndex, follow, items, listState) {
        if (follow && items.isNotEmpty()) {
            val target = currentIndex.coerceAtLeast(0)
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.any {
                it.index == target && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
            }
            if (!visible) listState.scrollToItem(target)
        }
    }
    // Chronological order makes playback move forward through the list. Manual scroll pauses
    // following, not playback; Follow returns to the current event without stealing gestures.
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth().nestedScroll(manualScroll)
        .semantics { contentDescription = "$label events" }, state = listState) {
        if (items.isEmpty()) item {
            Text("No events in this track.", color = Muted, fontSize = 11.sp)
        }
        items(items.size, key = { it }) { index ->
            val item = items[index]
            val isPast = item.ts <= playhead
            val isCurrent = index == currentIndex
            Row(
                Modifier.fillMaxWidth()
                    .semantics { selected = isCurrent; stateDescription = if (isCurrent) "Current event" else if (isPast) "Past event" else "Upcoming event" }
                    .clickable(role = Role.Button) { onSeek(item.ts) }
                    .background(
                        if (isCurrent) Accent.copy(alpha = 0.10f) else Color.Transparent,
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Text(fmtFine(item.ts - startMs),
                    color = if (isCurrent) Accent else Muted, fontSize = 10.sp,
                    modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label.take(80) + if (item.label.length > 80) "…" else "",
                        color = when {
                            item.isError -> if (isPast) Err else Err.copy(alpha = 0.45f)
                            isCurrent -> Accent
                            isPast -> TextMain
                            else -> Muted.copy(alpha = 0.55f)
                        },
                        fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                    item.sub?.let {
                        Text(it.take(500) + if (it.length > 500) "…" else "",
                            color = Muted.copy(alpha = if (isPast) 1f else 0.55f), fontSize = 10.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryPane(summary: PSummary?) {
    if (summary == null) {
        Text("No summary in this recording.", color = Muted, fontSize = 11.sp)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Likely Owner", color = Muted, fontSize = 11.sp)
        Text("${summary.category}  ·  ${summary.confidence}", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        summary.reasons.forEach { Text("• $it", color = TextMain, fontSize = 11.sp) }

        if (summary.penalties.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Issue areas", color = Muted, fontSize = 11.sp)
            summary.penalties.forEach { (dim, pts) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dim, color = TextMain, fontSize = 11.sp)
                    Text("-$pts", color = Err, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Reproduction", color = Muted, fontSize = 11.sp)
        summary.steps.forEach { Text(it, color = TextMain, fontSize = 11.sp) }
        Spacer(Modifier.height(6.dp))
        Text("Expected: ${summary.expected}", color = Green, fontSize = 11.sp)
        Text("Actual: ${summary.actual}", color = Err, fontSize = 11.sp)
    }
}

@Composable
private fun StatePane(sample: PStateSample?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (sample == null) { Text("No state captured.", color = Muted, fontSize = 11.sp); return }
        Text("Screen: ${sample.screen ?: "—"}", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (sample.flags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Feature Flags", color = Muted, fontSize = 11.sp)
            sample.flags.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = TextMain, fontSize = 11.sp)
                    Text(if (v) "ON" else "OFF", color = if (v) Green else Muted, fontSize = 11.sp)
                }
            }
        }
        sample.data.forEach { (src, kv) ->
            Spacer(Modifier.height(6.dp))
            Text(src, color = Muted, fontSize = 11.sp)
            kv.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = Muted, fontSize = 11.sp)
                    Text(v, color = TextMain, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Ctrl(label: String, tint: Color = TextMain, description: String = label, onClick: () -> Unit) {
    Box(Modifier
            .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center
    ) { Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

private fun fmt(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Row/playhead timestamps include tenths — short recordings otherwise show 00:00 everywhere. */
private fun fmtFine(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val totalSec = clamped / 1000
    return "%02d:%02d.%d".format(totalSec / 60, totalSec % 60, (clamped % 1000) / 100)
}

/** Unwraps the Activity from a Compose context so Close can finish() and return to the caller. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
