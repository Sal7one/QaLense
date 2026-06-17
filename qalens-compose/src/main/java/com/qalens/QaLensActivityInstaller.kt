package com.qalens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.doOnAttach
import androidx.startup.Initializer
import com.qalens.android.QaLensNotification
import com.qalens.android.QaLensShakeDetector
import kotlin.math.roundToInt

class QaLensStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as? Application ?: return
        if (QaLens.config.value.enableAutoInstall) QaLens.install(application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

internal object QaLensActivityInstaller : Application.ActivityLifecycleCallbacks {
    private const val OVERLAY_TAG = "qalens_overlay_compose_view"
    private const val NOTIFICATION_PERMISSION_REQUEST = 0x4153

    private var installed = false
    private var notificationPermissionAsked = false

    // Live host-activity count → detect when the app is fully gone (vs. just rotating/backgrounded)
    // so QaLens tears down with it instead of leaving an orphaned ongoing notification.
    private var liveActivities = 0

    // One shake detector per process
    private var shakeDetector: QaLensShakeDetector? = null

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /** QaLens-owned screens (Control Room, Player, consent trampoline) never get the overlay. */
    private fun isInternal(activity: Activity): Boolean = when (activity.javaClass.name) {
        "com.qalens.QaLensControlActivity",
        "com.qalens.QaLensProjectionActivity",
        "com.qalens.replay.QaLensPlayerActivity" -> true
        else -> false
    }

    fun attachOverlay(activity: Activity) {
        if (isInternal(activity)) return
        if (!QaLens.state.value.overlayEnabled) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val overlay = ComposeView(activity).apply {
            tag = OVERLAY_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // DO NOT set isClickable = false or isFocusable = false here.
            // Those flags prevent Compose gesture detectors (clickable, pointerInput) from
            // receiving ACTION_DOWN, so buttons in the panel never fire. The Compose tree
            // already passes through touches that no composable consumes, so the underlying
            // app stays interactive without these flags.
            setContent { QaLensOverlay() }
        }

        decor.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay.bringToFront()
        overlay.doOnAttach { QaLens.refreshInspection(decor) }
    }

    /** Remove the QaLens overlay view from the activity ("stop injecting"). */
    fun detachOverlay(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(OVERLAY_TAG)?.let { decor.removeView(it) }
    }

    fun readVisibleNodes(rootView: View): List<InspectNode> = ComposeSemanticsReader.read(rootView)

    /** Raw semantics nodes (with live action handlers) — used by the macro UI driver (tap/type). */
    fun rawSemanticsNodes(activity: Activity): List<SemanticsNode> =
        ComposeSemanticsReader.rawNodes(activity.window.decorView)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!isInternal(activity)) liveActivities++
    }
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (isInternal(activity)) return
        // Master run-condition (`QaLens.configure { enabled = false }`): fully inert — no overlay,
        // no notification, no shake. Cleans up anything attached before the app configured it off.
        if (!QaLens.config.value.enabled) {
            detachOverlay(activity)
            QaLensNotification.dismiss(activity)
            return
        }
        QaLens.onActivityResumed(activity)
        attachOverlay(activity)
        healOverlayVisibility(activity)
        startShake(activity)
        maybeRequestNotificationPermission(activity)
        QaLensNotification.show(activity, QaLens.state.value.isRecording)

        // A recording armed in the Control Room starts now, on the first host-app screen.
        // (No log here — it would be captured as the first entry of the recording itself.)
        QaLens.consumePendingRecording()?.let { video ->
            QaLensSessionRecorder.start(activity, video)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (isInternal(activity)) return
        QaLens.onActivityPaused(activity)
        stopShake(activity)
    }

    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /**
     * When the host app's last activity is destroyed for real (not a rotation/config change), the
     * app is gone — so QaLens tears down with it: discard any in-flight recording, drop the stop
     * chip, and dismiss the ongoing notification (which would otherwise linger in the shade after
     * the app is swiped away). Re-shown automatically on the next launch.
     */
    override fun onActivityDestroyed(activity: Activity) {
        if (isInternal(activity)) return
        liveActivities = (liveActivities - 1).coerceAtLeast(0)
        if (liveActivities == 0 && !activity.isChangingConfigurations) {
            if (QaLens.state.value.isRecording) QaLensSessionRecorder.cancel()
            QaLensSystemChip.hide()
            QaLensNotification.dismiss(activity)
        }
    }

    /**
     * Self-healing: an overlay must never stay invisible outside an active recording (this is what
     * used to permanently strand QA when the recording state machine broke). During a recording the
     * overlay is hidden in BOTH chip modes (the stop chip lives in its own window), and the in-app
     * chip — which dies with its activity — gets re-attached to whichever activity resumed.
     */
    private fun healOverlayVisibility(activity: Activity) {
        val recording = QaLens.state.value.isRecording
        QaLensScreenCapture.setOverlayVisible(activity, !recording)
        if (recording && !QaLensSessionRecorder.usesSystemChip) {
            QaLensSystemChip.ensureInApp(activity)
        }
    }

    // ── Notification permission (Android 13+) ────────────────────────────────
    // Without this the QaLens notification — including its Stop Recording action — is silently
    // dropped, which was the root cause of "recording starts and can't be stopped".
    private fun maybeRequestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionAsked) return
        notificationPermissionAsked = true
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return
        runCatching {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    // ── Shake ────────────────────────────────────────────────────────────────

    private fun startShake(activity: Activity) {
        if (shakeDetector != null) return
        shakeDetector = QaLensShakeDetector {
            // While recording the panel is unreachable — shake becomes the emergency stop.
            if (QaLens.state.value.isRecording) QaLens.stopRecording() else QaLens.togglePanel()
        }
        QaLensShakeDetector.register(activity, shakeDetector!!)
    }

    private fun stopShake(activity: Activity) {
        shakeDetector?.let { QaLensShakeDetector.unregister(activity, it) }
        shakeDetector = null
    }
}

private object ComposeSemanticsReader {
    private const val OVERLAY_TAG = "qalens_overlay_compose_view"

    fun read(rootView: View): List<InspectNode> {
        val density = rootView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        return rawNodes(rootView)
            .mapNotNull { node -> node.toInspectNode(density) }
            .filterNot { it.hiddenFromReports }
            .distinctBy { it.id }
    }

    /**
     * Raw SemanticsNodes with their action handlers intact. Uses the STABLE public path: every
     * AndroidComposeView implements [androidx.compose.ui.node.RootForTest] (`semanticsOwner` +
     * public `getAllSemanticsNodes`). The previous reflection on internal members silently broke
     * against newer Compose (1.8+/BOM 2025.x name-mangles them) — no reflection, no breakage.
     */
    fun rawNodes(rootView: View): List<SemanticsNode> =
        findComposeRoots(rootView).flatMap { root ->
            runCatching {
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            }.getOrDefault(emptyList())
        }

    private fun findComposeRoots(view: View): List<androidx.compose.ui.node.RootForTest> {
        if (view.tag == OVERLAY_TAG) return emptyList()
        val result = mutableListOf<androidx.compose.ui.node.RootForTest>()
        if (view is androidx.compose.ui.node.RootForTest) result += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) result += findComposeRoots(view.getChildAt(i))
        }
        return result
    }

    private fun SemanticsNode.toInspectNode(density: Float): InspectNode? {
        val config = this.config
        val bounds = this.boundsInWindow
        val rect   = QaRect(
            left   = bounds.left.roundToInt(),
            top    = bounds.top.roundToInt(),
            right  = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt()
        )
        if (rect.width <= 0 || rect.height <= 0) return null

        val testTag            = config.getOrNull(SemanticsProperties.TestTag)
        val text               = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        val contentDescription = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val role               = config.getOrNull(SemanticsProperties.Role)?.toString()
        val stateDescription   = config.getOrNull(SemanticsProperties.StateDescription)
        val selected           = config.getOrNull(SemanticsProperties.Selected) ?: false
        val disabled           = config.contains(SemanticsProperties.Disabled)
        val qaName             = config.getOrNull(QaNameKey)
        val hidden             = config.getOrNull(QaHiddenFromReportsKey) ?: false

        return InspectNode(
            id                 = "semantics:${this.id}",
            testTag            = testTag,
            qaName             = qaName,
            contentDescription = contentDescription,
            text               = text,
            role               = role,
            stateDescription   = stateDescription,
            isEnabled          = !disabled,
            isClickable        = config.contains(SemanticsActions.OnClick),
            isFocusable        = config.contains(SemanticsProperties.Focused),
            isSelected         = selected,
            isHeading          = config.getOrNull(SemanticsProperties.Heading) != null,
            bounds             = rect,
            widthDp            = rect.width / density,
            heightDp           = rect.height / density,
            source             = if (qaName != null) NodeSource.MANUAL_QA_TAG else NodeSource.SEMANTICS,
            hiddenFromReports  = hidden
        )
    }
}
