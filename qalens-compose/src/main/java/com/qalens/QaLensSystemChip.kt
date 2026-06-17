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

    private var chip: TextView? = null
    private var windowManager: WindowManager? = null
    private var inAppActivity: WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startMs = 0L

    private val timeTick = object : Runnable {
        override fun run() {
            val view = chip ?: return
            val sec = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(0)
            view.text = "■ REC  %d:%02d".format(sec / 60, sec % 60)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(context: Context, wm: WindowManager, type: Int, activity: Activity?) {
        val density = context.resources.displayMetrics.density

        val view = TextView(context).apply {
            text = "■ REC  0:00"
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (64 * density).toInt()
            activity?.let { token = it.window.attributes.token }
        }

        // Drag to move; treat sub-slop movement as a tap → stop recording.
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
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) QaLens.stopRecording()
                }
                return true
            }
        })

        runCatching { wm.addView(view, params) }
            .onSuccess {
                chip = view
                windowManager = wm
                inAppActivity = activity?.let { WeakReference(it) }
                startMs = System.currentTimeMillis()
                handler.removeCallbacks(timeTick)
                handler.post(timeTick)
            }
            .onFailure { QaLens.log("Recording stop chip failed: ${it.message}") }
    }

    fun hide() {
        handler.removeCallbacks(timeTick)
        val view = chip ?: return
        chip = null
        runCatching { windowManager?.removeViewImmediate(view) }
        windowManager = null
        inAppActivity = null
    }
}
