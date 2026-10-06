package com.qalens

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** A second finger switches an inspect gesture to a one-finger host drag.
 * Unconsumed Compose events cannot cross the decor's sibling View boundary.
 */
internal class QaLensOverlayHost(context: Context, private val touchTarget: (() -> View?)? = null) : FrameLayout(context) {
    private val activity = context as? Activity
    private var target: View? = null
    private var forwarding = false
    private var suppressUntilUp = false
    private var hostDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragged = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelHost(event.eventTime)
            suppressUntilUp = false
        }
        val state = QaLens.state.value
        val canScroll = QaLens.config.value.enabled && (state.isInspectMode || state.isTagMode) &&
            !state.isPanelOpen && !state.isRecording && !state.isWatchMode
        if (forwarding && !canScroll) {
            cancelHost(event.eventTime)
            suppressUntilUp = true
        }
        if (!forwarding && !suppressUntilUp && canScroll &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // End the inspector's pending tap before handing the drag to the host.
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            // A dismissed dialog must never redirect its in-flight gesture to the Activity below.
            target = (if (touchTarget != null) touchTarget.invoke() else activity?.findViewById<View>(android.R.id.content))
                ?.takeIf { it.isAttachedToWindow }
            forwarding = target != null
            suppressUntilUp = true
            hostDownTime = event.eventTime
            updateCentroid(event)
            startX = lastX; startY = lastY; dragged = false
            if (forwarding) sendHost(MotionEvent.ACTION_DOWN, event.eventTime)
        } else if (forwarding) {
            // End when either finger lifts; the remaining finger must never click the host.
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP) {
                // A stationary two-finger touch is not permission to click the host.
                sendHost(if (dragged) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, event.eventTime)
                forwarding = false
                target = null
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                cancelHost(event.eventTime)
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                updateCentroid(event)
                if (kotlin.math.hypot(lastX - startX, lastY - startY) > touchSlop) dragged = true
                sendHost(MotionEvent.ACTION_MOVE, event.eventTime)
            }
        }
        if (suppressUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                suppressUntilUp = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun updateCentroid(event: MotionEvent) {
        val host = target ?: return
        val overlayLocation = IntArray(2)
        val hostLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        host.getLocationOnScreen(hostLocation)
        lastX = (event.getX(0) + event.getX(1)) / 2 + overlayLocation[0] - hostLocation[0]
        lastY = (event.getY(0) + event.getY(1)) / 2 + overlayLocation[1] - hostLocation[1]
    }

    private fun sendHost(action: Int, time: Long) {
        val event = MotionEvent.obtain(hostDownTime, time, action, lastX, lastY, 0)
        try { target?.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun cancelHost(time: Long) {
        if (forwarding) sendHost(MotionEvent.ACTION_CANCEL, time)
        forwarding = false
        target = null
    }

    override fun onDetachedFromWindow() {
        cancelHost(android.os.SystemClock.uptimeMillis())
        super.onDetachedFromWindow()
    }
}
