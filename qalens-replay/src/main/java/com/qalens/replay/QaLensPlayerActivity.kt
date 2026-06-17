package com.qalens.replay

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    fun load(uri: Uri, fromIntent: Boolean) {
        scope.launch {
            loading = true; error = null
            try {
                session = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { QaLensSalReader.read(context, it) }
                        ?: error("Could not open file")
                }
                loadedFromIntent = fromIntent
            } catch (e: Exception) {
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
            current != null -> PlayerScreen(current) {
                // Came from the Control Room / a shared file → finish so we return there.
                // Opened a file via the in-app picker → back to the picker landing.
                if (loadedFromIntent) context.findActivity()?.finish() else session = null
            }
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
    SUMMARY("Summary"), TIMELINE("Timeline"), NETWORK("Network"), LOGS("Logs"), STATE("State")
}

private fun scoreColor(score: Int): Color = when {
    score >= 85 -> Green
    score >= 70 -> Color(0xFFFBBF24)
    score >= 50 -> Color(0xFFFB923C)
    else -> Err
}

@Composable
private fun PlayerScreen(session: PlayerSession, onClose: () -> Unit) {
    var playhead by remember { mutableStateOf(session.startMs) }
    var playing by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf(if (session.summary != null) Track.SUMMARY else Track.TIMELINE) }
    val context = LocalContext.current

    // Optional MediaProjection video track — when present, ExoPlayer is the playback clock.
    val exo = remember(session.videoFile) {
        session.videoFile?.let { f ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
                prepare()
            }
        }
    }
    DisposableEffect(exo) { onDispose { exo?.release() } }

    // The video track starts at consent-grant time, NOT at session start. All video<->timeline
    // mapping must go through this base or seeks land early by the consent delay. Old recordings
    // without videoStartMillis fall back to aligning the video's END to the session end.
    fun videoBase(): Long = session.videoStartMs
        ?: exo?.duration?.takeIf { it > 0 }?.let { session.endMs - it }
        ?: session.startMs

    fun seekTo(ts: Long) {
        playhead = ts.coerceIn(session.startMs, session.endMs)
        exo?.seekTo((playhead - videoBase()).coerceAtLeast(0))
    }

    // Playback loop: video follows ExoPlayer's position; frame mode advances ~real time.
    LaunchedEffect(playing) {
        if (exo != null) {
            exo.playWhenReady = playing
            while (playing) {
                kotlinx.coroutines.delay(100)
                playhead = (videoBase() + exo.currentPosition)
                    .coerceIn(session.startMs, session.endMs)
                if (exo.playbackState == Player.STATE_ENDED) playing = false
            }
            exo.playWhenReady = false
        } else {
            while (playing) {
                kotlinx.coroutines.delay(200)
                playhead = (playhead + 200).coerceAtMost(session.endMs)
                if (playhead >= session.endMs) playing = false
            }
        }
    }

    fun stepNext() { session.allEvents.firstOrNull { it.ts > playhead }?.let { playing = false; seekTo(it.ts) } }
    fun stepPrev() { session.allEvents.lastOrNull { it.ts < playhead }?.let { playing = false; seekTo(it.ts) } }
    fun firstError() { session.allEvents.firstOrNull { it.isError }?.let { playing = false; seekTo(it.ts) } }

    if (fullscreen) {
        // ── Theatre mode: media fills the screen; slim transport overlaid at the bottom ──
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MediaViewport(session, exo, playhead, Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xD90E1116))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Slider(
                    value = (playhead - session.startMs).toFloat().coerceIn(0f, session.durationMs.toFloat()),
                    onValueChange = { playing = false; seekTo(session.startMs + it.toLong()) },
                    valueRange = 0f..session.durationMs.toFloat()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ctrl("⏮") { stepPrev() }
                    Ctrl(if (playing) "⏸" else "▶") { playing = !playing }
                    Ctrl("⏭") { stepNext() }
                    Text(
                        "${fmtFine(playhead - session.startMs)} / ${fmt(session.durationMs)}",
                        color = Muted, fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Ctrl("⛶ exit", Accent) { fullscreen = false }
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
                Text("${session.frames.size} frames · ${session.durationMs / 1000}s · ${session.fps}fps",
                    color = Muted, fontSize = 10.sp)
            }
            Text(fmtFine(playhead - session.startMs), color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))

        // Viewport — video (ExoPlayer) when present, else the nearest captured frame.
        // Flexible height (not a fixed aspect ratio): it shares the screen with the track pane
        // below, so Summary/Timeline/Logs always stay visible on phones. Media letterboxes inside.
        // Tap the media (or the ⛶ button) for fullscreen theatre mode.
        MediaViewport(
            session, exo, playhead,
            Modifier.fillMaxWidth()
                .weight(1.15f)
                .heightIn(min = 160.dp)
                .background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable { fullscreen = true }
        )

        Spacer(Modifier.height(6.dp))

        // Scrubber
        Slider(
            value = (playhead - session.startMs).toFloat().coerceIn(0f, session.durationMs.toFloat()),
            onValueChange = { playing = false; seekTo(session.startMs + it.toLong()) },
            valueRange = 0f..session.durationMs.toFloat()
        )

        // Controls
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Ctrl("⏮") { stepPrev() }
            Ctrl(if (playing) "⏸" else "▶") { playing = !playing }
            Ctrl("⏭") { stepNext() }
            Ctrl("⛶") { fullscreen = true }
            Spacer(Modifier.weight(1f))
            Ctrl("⚠ error", Err) { firstError() }
        }

        Spacer(Modifier.height(8.dp))

        // Track tabs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Track.entries.forEach { t ->
                val active = t == track
                Text(t.label, color = if (active) Accent else Muted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 12.sp,
                    modifier = Modifier.clickable { track = t }.padding(horizontal = 8.dp, vertical = 4.dp))
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
                    EventPane(items, playhead, session.startMs) { ts -> playing = false; seekTo(ts) }
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
    modifier: Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (exo != null) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exo; useController = false } },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val frame = session.frameAt(playhead)
            val bitmap = remember(frame?.file?.path) {
                frame?.file?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "frame", modifier = Modifier.fillMaxSize())
            } else {
                Text("no frame", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EventPane(items: List<PItem>, playhead: Long, startMs: Long, onSeek: (Long) -> Unit) {
    // All events are listed (newest first); ones after the playhead are dimmed. Tapping any row
    // seeks the video to that exact moment — including forward jumps.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (items.isEmpty()) {
            Text("No events in this track.", color = Muted, fontSize = 11.sp)
        }
        val currentTs = items.lastOrNull { it.ts <= playhead }?.ts
        items.asReversed().forEach { item ->
            val isPast = item.ts <= playhead
            val isCurrent = item.ts == currentTs
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSeek(item.ts) }
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
                    Text(item.label.take(80),
                        color = when {
                            item.isError -> if (isPast) Err else Err.copy(alpha = 0.45f)
                            isCurrent -> Accent
                            isPast -> TextMain
                            else -> Muted.copy(alpha = 0.55f)
                        },
                        fontSize = 11.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                    item.sub?.let {
                        Text(it, color = Muted.copy(alpha = if (isPast) 1f else 0.55f), fontSize = 10.sp)
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
private fun Ctrl(label: String, tint: Color = TextMain, onClick: () -> Unit) {
    Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp))
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
