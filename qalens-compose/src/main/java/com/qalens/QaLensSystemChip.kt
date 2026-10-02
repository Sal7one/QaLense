package com.qalens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.EditText
import android.app.AlertDialog
import com.qalens.compose.R
import java.lang.ref.WeakReference

/**
 * Floating "■ REC 0:42" stop chip shown while a session records. Two modes, both in their OWN
 * window so the PixelCopy frame recorder never captures it (it snapshots only the activity's
 * window) and nothing has to blink per frame:
 *
 *  - **Overlay mode** (`TYPE_APPLICATION_OVERLAY`): needs "draw over other apps"; survives leaving
 *    the app — the premium experience.
 *  - **In-app mode** (`TYPE_APPLICATION`, attached to the current activity): NO permission needed.
 *    It dies with its activity, so the installer re-attaches it on every host-activity resume
 *    while a recording is live.
 *
 * Plain views (not Compose): a raw window has no lifecycle/savedstate owners. Draggable; tap stops.
 */
internal object QaLensSystemChip {

    private var chipRef: WeakReference<TextView>? = null
    private val chip: TextView? get() = chipRef?.get()
    private var chipWindowRef: WeakReference<View>? = null
    private val chipWindow: View? get() = chipWindowRef?.get()
    private var windowManager: WindowManager? = null
    private var inAppActivity: WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startMs = 0L

    private val timeTick = object : Runnable {
        override fun run() {
            val view = chip ?: return
            val sec = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(0)
            val minutes = (sec / 60).toInt()
            val seconds = (sec % 60).toInt()
            view.text = view.context.getString(R.string.qalens_recording_elapsed, minutes, seconds)
            view.contentDescription = view.context.getString(R.string.qalens_stop_recording_elapsed, minutes, seconds)
            handler.postDelayed(this, 1000L)
        }
    }

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Overlay mode — requires draw-over-apps; visible across the whole device. */
    fun show(context: Context) {
        if (chip != null) return
        if (!canShow(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        attach(app, wm, type, null)
    }

    /** In-app mode — no permission; its window belongs to [activity] (re-attach on resume). */
    fun showInApp(activity: Activity) {
        if (chip != null && inAppActivity?.get() === activity) return
        hide()
        attach(
            activity,
            activity.windowManager,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            activity
        )
    }

    /** Keep the in-app chip alive across activity changes while recording. */
    fun ensureInApp(activity: Activity) {
        if (inAppActivity == null && chip != null) return       // overlay mode — nothing to do
        if (inAppActivity?.get() !== activity || chip == null) {
            val elapsedStart = if (chip != null) startMs else 0L
            showInApp(activity)
            if (elapsedStart > 0L) startMs = elapsedStart       // keep the running timer
        }
    }

    @SuppressLint("RtlHardcoded") // Window x/drag deltas are physical screen coordinates in both locales.
    private fun attach(context: Context, wm: WindowManager, type: Int, activity: Activity?) {
        val density = context.resources.displayMetrics.density

        val view = object : TextView(context) {
            override fun performClick(): Boolean {
                super.performClick()
                QaLens.stopRecording()
                return true
            }
        }.apply {
            text = context.getString(R.string.qalens_recording_elapsed, 0, 0)
            contentDescription = context.getString(R.string.qalens_stop_recording_elapsed, 0, 0)
            isClickable = true
            minHeight = (48 * density).toInt()
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            val pad = (10 * density).toInt()
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.argb(235, 190, 30, 40))
                setStroke((1.5f * density).toInt(), Color.argb(160, 255, 255, 255))
            }
            elevation = 8 * density
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(view)
            addView(TextView(context).apply {
                text = context.getString(R.string.qalens_clip_button); contentDescription = context.getString(R.string.qalens_clip_description); textSize = 14f
                setTextColor(Color.WHITE); setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                minHeight = (48 * density).toInt(); isClickable = true
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                    }
                }
                background = GradientDrawable().apply { cornerRadius = 24 * density; setColor(Color.rgb(45, 65, 90)) }
                setOnClickListener { anchor ->
                    runCatching { PopupMenu(context, anchor).apply {
                        val presets = QaLens.config.value.recordingClipPresetsSeconds.filter { it in 1..300 }.distinct().take(6).ifEmpty { listOf(10, 20, 60) }
                        presets.forEach { seconds -> menu.add(context.getString(R.string.qalens_clip_last_seconds, seconds)).setOnMenuItemClickListener { QaLens.saveRecentClip(seconds); true } }
                        menu.add(context.getString(R.string.qalens_clip_custom)).setOnMenuItemClickListener {
                            val host = QaLens.currentActivity?.takeIf { QaLensActivityInstaller.isResumed(it) && !it.isFinishing }
                            if (host != null) {
                                val input = EditText(host).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(30.toString()); hint = host.getString(R.string.qalens_clip_hint) }
                                runCatching { AlertDialog.Builder(host).setTitle(R.string.qalens_clip_title).setView(input)
                                    .setPositiveButton(R.string.qalens_clip_mark) { _, _ -> QaLens.saveRecentClip(input.text.toString().toIntOrNull() ?: 0) }
                                    .setNegativeButton(R.string.qalens_cancel, null).show() }
                                    .onFailure { QaLens.pushError(ErrorKind.RECORDING, "Open the host app to choose a custom clip duration.") }
                            } else android.widget.Toast.makeText(context, context.getString(R.string.qalens_clip_open_app), android.widget.Toast.LENGTH_SHORT).show()
                            true
                        }
                        show()
                    } }.onFailure { QaLens.pushError(ErrorKind.RECORDING, "Clip menu unavailable: ${it.message}") }
                }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (16 * density).toInt()
            y = (64 * density).toInt()
            activity?.let { token = it.window.attributes.token }
        }

        // Drag to move; sub-slop movement calls performClick, including the accessibility action.
        @SuppressLint("ClickableViewAccessibility") // The TextView subclass above overrides performClick.
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (moved || dx * dx + dy * dy > 24 * 24 * density * density / 4) {
                            moved = true
                            val decor = activity?.window?.decorView ?: QaLens.currentActivity?.window?.decorView ?: container
                            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
                            val left = insets?.left ?: 0; val top = insets?.top ?: (24 * density).toInt()
                            val right = insets?.right ?: 0; val bottom = insets?.bottom ?: (48 * density).toInt()
                            params.x = (startX + dx.toInt()).coerceIn(left, maxOf(left, context.resources.displayMetrics.widthPixels - right - container.width))
                            params.y = (startY + dy.toInt()).coerceIn(top, maxOf(top, context.resources.displayMetrics.heightPixels - bottom - container.height))
                            runCatching { wm.updateViewLayout(container, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) v.performClick()
                }
                return true
            }
        })

        runCatching { wm.addView(container, params) }
            .onSuccess {
                chipRef = WeakReference(view)
                chipWindowRef = WeakReference(container)
                windowManager = wm
                inAppActivity = activity?.let { WeakReference(it) }
                startMs = QaLensSessionRecorder.captureStartedAt
                handler.removeCallbacks(timeTick)
                handler.post(timeTick)
            }
            .onFailure { QaLens.log("Recording stop chip failed: ${it.message}") }
    }

    fun hide() {
        handler.removeCallbacks(timeTick)
        val view = chipWindow ?: return
        chipRef = null
        chipWindowRef = null
        runCatching { windowManager?.removeViewImmediate(view) }
        windowManager = null
        inAppActivity = null
    }

    /** The permission-free chip is tied to one Activity window; release it on destruction. */
    fun detachFrom(activity: Activity) {
        if (inAppActivity?.get() === activity) hide()
    }
}
