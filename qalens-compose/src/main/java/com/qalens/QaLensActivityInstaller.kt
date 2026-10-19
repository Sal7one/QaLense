package com.qalens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
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
    internal const val OVERLAY_TAG = "qalens_overlay_compose_view"
    private const val NOTIFICATION_PERMISSION_REQUEST = 0x4153

    private var installed = false
    private var callbacksRegistered = false
    internal val isInstalled: Boolean get() = installed
    private val rootActivities = java.util.WeakHashMap<Activity, Int>()
    private var notificationPermissionAsked = false

    // Live host-activity count → detect when the app is fully gone (vs. just rotating/backgrounded)
    // so QaLens tears down with it instead of leaving an orphaned ongoing notification.
    private var liveActivities = 0
    private val resumed = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    private val activities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    private data class ExtraRoot(val owner: java.lang.ref.WeakReference<Activity>, var references: Int, val order: Long)
    private val extraRoots = java.util.WeakHashMap<View, ExtraRoot>()
    private var nextExtraRootOrder = 0L

    fun registerInspectionRoot(view: View) {
        val activity = view.context.findActivity() ?: return
        synchronized(extraRoots) {
            val existing = extraRoots[view]
            if (existing?.owner?.get() === activity) existing.references++
            else extraRoots[view] = ExtraRoot(java.lang.ref.WeakReference(activity), 1, ++nextExtraRootOrder)
        }
        QaLens.scheduleInspection()
    }

    fun unregisterInspectionRoot(view: View) {
        synchronized(extraRoots) {
            val entry = extraRoots[view] ?: return@synchronized
            if (--entry.references <= 0) extraRoots.remove(view)
        }
        QaLens.scheduleInspection()
    }

    fun isResumed(activity: Activity): Boolean = activity in resumed

    fun semanticsId(node: SemanticsNode): String = ComposeSemanticsReader.semanticsId(node)

    fun inspectionRoots(activity: Activity?): List<View> = if (activity == null) emptyList() else
        synchronized(extraRoots) {
            extraRoots.entries.filter { (view, entry) -> entry.owner.get() === activity && view.isAttachedToWindow }
                .sortedBy { it.value.order }.map { it.key }
        }

    fun inspectionWindowFor(id: String): View? = ComposeSemanticsReader.windowFor(id)

    /** Align qaTag layout hints from Dialog/Popup windows with the host decor coordinate space. */
    fun mapToHostWindow(view: View, bounds: Rect): Rect {
        val decor = QaLens.currentActivity?.window?.decorView ?: return bounds
        val sourceScreen = IntArray(2)
        val sourceWindow = IntArray(2)
        val targetScreen = IntArray(2)
        val targetWindow = IntArray(2)
        view.getLocationOnScreen(sourceScreen)
        view.getLocationInWindow(sourceWindow)
        decor.getLocationOnScreen(targetScreen)
        decor.getLocationInWindow(targetWindow)
        val dx = (sourceScreen[0] - sourceWindow[0] - targetScreen[0] + targetWindow[0]).toFloat()
        val dy = (sourceScreen[1] - sourceWindow[1] - targetScreen[1] + targetWindow[1]).toFloat()
        return bounds.translate(dx, dy)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun belongsToActivity(view: View, activity: Activity?): Boolean =
        activity != null && view.context.findActivity() === activity

    private val layoutListeners = java.util.WeakHashMap<Activity, android.view.ViewTreeObserver.OnGlobalLayoutListener>()
    private val focusListeners = java.util.WeakHashMap<Activity, android.view.ViewTreeObserver.OnWindowFocusChangeListener>()
    private fun observeLayout(activity: Activity) {
        if (layoutListeners.containsKey(activity)) return
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { QaLens.scheduleInspection() }
        layoutListeners[activity] = listener
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { QaLens.scheduleInspection() }
        focusListeners[activity] = focus
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
    }
    private fun stopLayout(activity: Activity) {
        val observer = activity.window.decorView.viewTreeObserver
        layoutListeners.remove(activity)?.let { if (observer.isAlive) observer.removeOnGlobalLayoutListener(it) }
        focusListeners.remove(activity)?.let { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(it) }
    }

    fun suspendCapture() {
        activities.toList().forEach { stopLayout(it); detachOverlay(it); QaLensFrameMetrics.detach(it); stopShake(it) }
    }

    fun resumeCapture() { resumed.toList().forEach(::onActivityResumed) }


    // One shake detector per process
    private var shakeDetector: QaLensShakeDetector? = null

    fun install(application: Application) {
        if (installed) return
        installed = true
        registerCallbacks(application)
    }

    private fun registerCallbacks(application: Application) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /** Explicit roots opt in only their Activity when AndroidX Startup is absent/disabled. */
    fun registerRoot(activity: Activity) {
        QaLens.rememberApplication(activity.application)
        rootActivities[activity] = (rootActivities[activity] ?: 0) + 1
        registerCallbacks(activity.application)
        onActivityCreated(activity, null) // Root composition may occur after the real callback.
    }

    fun resumeRoot(activity: Activity) {
        if (!isResumed(activity)) onActivityResumed(activity)
    }

    fun unregisterRoot(activity: Activity) {
        val count = rootActivities[activity] ?: return
        if (count > 1) { rootActivities[activity] = count - 1; return }
        if (!installed) {
            onActivityPaused(activity)
            detachOverlay(activity)
            onActivityDestroyed(activity)
        }
        rootActivities.remove(activity)
    }

    private fun isManaged(activity: Activity): Boolean =
        !isInternal(activity) && (installed || rootActivities.containsKey(activity))

    /** QaLens-owned screens (Control Room, Player, consent trampoline) never get the overlay. */
    private fun isInternal(activity: Activity): Boolean = when (activity.javaClass.name) {
        "com.qalens.QaLensControlActivity",
        "com.qalens.QaLensProjectionActivity",
        "com.qalens.replay.QaLensPlayerActivity" -> true
        else -> false
    }

    fun attachOverlay(activity: Activity) {
        if (isInternal(activity) || !QaLens.config.value.enabled) return
        if (!QaLens.state.value.overlayEnabled) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val compose = ComposeView(activity).apply {
            tag = OVERLAY_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // DO NOT set isClickable = false or isFocusable = false here.
            // Those flags prevent Compose gesture detectors (clickable, pointerInput) from
            // receiving ACTION_DOWN, so buttons in the panel never fire. The Compose tree
            // leaves unused regions available to the host. The parent QaLensOverlayHost
            // explicitly routes two-finger inspect/tag drags across the sibling View boundary.
            setContent { QaLensOverlay() }
        }

        val overlay = QaLensOverlayHost(activity).apply {
            tag = OVERLAY_TAG
            addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        QaLensInspectionWindows.dismiss(activity)
        val decor = activity.window.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(OVERLAY_TAG)?.let { decor.removeView(it) }
    }

    fun readVisibleNodes(rootView: View): List<InspectNode> = ComposeSemanticsReader.read(rootView)

    /** Raw semantics nodes (with live action handlers) — used by the macro UI driver (tap/type). */
    fun rawSemanticsNodes(activity: Activity): List<SemanticsNode> =
        ComposeSemanticsReader.rawNodes(activity.window.decorView)

    /** Capture must fail closed if a Compose root cannot be read. */
    fun captureSemanticsNodes(activity: Activity): List<SemanticsNode> =
        ComposeSemanticsReader.rawNodes(activity.window.decorView, strict = true)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (isManaged(activity) && activities.add(activity)) liveActivities++
    }
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (!isManaged(activity)) return
        onActivityCreated(activity, null) // Installation can happen while a host task already exists.
        resumed += activity
        QaLens.trackActivity(activity)
        // Master run-condition (`QaLens.configure { enabled = false }`): fully inert — no overlay,
        // no notification, no shake. Cleans up anything attached before the app configured it off.
        if (!QaLens.config.value.enabled) {
            detachOverlay(activity)
            QaLensNotification.dismiss(activity)
            return
        }
        QaLens.onActivityResumed(activity)
        observeLayout(activity)
        attachOverlay(activity)
        QaLensFrameMetrics.attach(activity)
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
        if (!isManaged(activity)) return
        QaLensInspectionWindows.dismiss(activity)
        resumed -= activity
        stopLayout(activity)
        QaLens.onActivityPaused(activity)
        QaLensFrameMetrics.detach(activity)
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
        if (!isManaged(activity) || !activities.remove(activity)) return
        QaLensInspectionWindows.dismiss(activity)
        stopLayout(activity)
        QaLensFrameMetrics.detach(activity)
        QaLensSystemChip.detachFrom(activity)
        resumed -= activity
        synchronized(extraRoots) { extraRoots.entries.removeAll { it.value.owner.get() === activity } }
        liveActivities = (liveActivities - 1).coerceAtLeast(0)
        if (liveActivities == 0 && !activity.isChangingConfigurations) {
            if (QaLens.state.value.isRecording) QaLensSessionRecorder.stop(share = false)
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
    private val rootIds = java.util.WeakHashMap<androidx.compose.ui.node.RootForTest, Long>()
    private var nextRootId = 0L

    fun read(rootView: View): List<InspectNode> {
        val density = rootView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val targetOrigin = windowOrigin(rootView)
        val rootOrigins = java.util.IdentityHashMap<androidx.compose.ui.node.RootForTest, Pair<Int, Int>>()
        val raw = rawNodes(rootView)
        val merged = findComposeRoots(rootView).flatMap { root ->
            runCatching { root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
                .getOrDefault(emptyList())
        }.associateBy { node -> node.root?.let { "${rootId(it)}:${node.id}" } }
        val ancestorsOfHidden = buildSet {
            raw.filter { it.config.getOrNull(QaHiddenFromReportsKey) == true }.forEach { hidden ->
                var ancestor = hidden.parent
                while (ancestor != null) {
                    ancestor.root?.let { add("${rootId(it)}:${ancestor.id}") }
                    ancestor = ancestor.parent
                }
            }
        }
        return raw
            .mapNotNull { node ->
                val root = node.root ?: return@mapNotNull null
                val key = "${rootId(root)}:${node.id}"
                val sourceOrigin = rootOrigins.getOrPut(root) {
                    (root as? View)?.let(::windowOrigin) ?: targetOrigin
                }
                node.toInspectNode(
                    density, rootView.width, rootView.height,
                    sourceOrigin.first - targetOrigin.first,
                    sourceOrigin.second - targetOrigin.second,
                    node.isHiddenFromReports(),
                    if (key in ancestorsOfHidden) null else merged[key]?.config
                )
            }
            .distinctBy { it.id }
    }

    private fun windowOrigin(view: View): Pair<Int, Int> {
        val screen = IntArray(2)
        val window = IntArray(2)
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        return (screen[0] - window[0]) to (screen[1] - window[1])
    }

    private fun rootId(root: androidx.compose.ui.node.RootForTest): Long = synchronized(rootIds) {
        rootIds.getOrPut(root) { ++nextRootId }
    }

    fun semanticsId(node: SemanticsNode): String = "semantics:${node.root?.let(::rootId)}:${node.id}"

    fun windowFor(id: String): View? {
        val rootId = id.takeIf { it.startsWith("semantics:") }?.split(':', limit = 3)?.getOrNull(1)?.toLongOrNull() ?: return null
        return synchronized(rootIds) { (rootIds.entries.firstOrNull { it.value == rootId }?.key as? View)?.rootView }
    }

    private fun SemanticsNode.isHiddenFromReports(): Boolean {
        var current: SemanticsNode? = this
        while (current != null) {
            if (current.config.getOrNull(QaHiddenFromReportsKey) == true) return true
            current = current.parent
        }
        return false
    }

    /**
     * Raw SemanticsNodes with their action handlers intact. Uses the STABLE public path: every
     * AndroidComposeView implements [androidx.compose.ui.node.RootForTest] (`semanticsOwner` +
     * public `getAllSemanticsNodes`). The previous reflection on internal members silently broke
     * against newer Compose (1.8+/BOM 2025.x name-mangles them) — no reflection, no breakage.
     */
    fun rawNodes(rootView: View, strict: Boolean = false): List<SemanticsNode> =
        findComposeRoots(rootView).flatMap { root ->
            runCatching {
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            }.getOrElse { if (strict) throw it else emptyList() }
        }

    private fun findComposeRoots(view: View): List<androidx.compose.ui.node.RootForTest> {
        val result = mutableListOf<androidx.compose.ui.node.RootForTest>()
        val seen = java.util.IdentityHashMap<androidx.compose.ui.node.RootForTest, Boolean>()
        fun collect(current: View) {
            if (current.tag == OVERLAY_TAG) return
            if (current is androidx.compose.ui.node.RootForTest && seen.put(current, true) == null) result += current
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) collect(current.getChildAt(i))
            }
        }
        collect(view)
        QaLensInspectionWindows.roots(QaLens.currentActivity).forEach(::collect)
        return result
    }

    private fun SemanticsNode.toInspectNode(
        density: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        offsetX: Int,
        offsetY: Int,
        inheritedHidden: Boolean,
        accessibleConfig: SemanticsConfiguration?
    ): InspectNode? {
        val config = this.config
        val bounds = this.boundsInWindow
        val rect   = QaRect(
            left   = bounds.left.roundToInt() + offsetX,
            top    = bounds.top.roundToInt() + offsetY,
            right  = bounds.right.roundToInt() + offsetX,
            bottom = bounds.bottom.roundToInt() + offsetY
        )
        if (rect.width <= 0 || rect.height <= 0 ||
            rect.right <= 0 || rect.bottom <= 0 || rect.left >= viewportWidth || rect.top >= viewportHeight) return null

        val testTag            = config.getOrNull(SemanticsProperties.TestTag)
        val text               = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            .ifEmpty { accessibleConfig?.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }
        // A merged clickable row can inherit both a child's text and an image description.
        // Prefer the visible text in that case; copying the image description onto the parent
        // creates a duplicate accessibility warning for a single control.
        val contentDescription = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            .ifEmpty {
                if (text.isEmpty()) accessibleConfig?.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                else emptyList()
            }
        val role               = config.getOrNull(SemanticsProperties.Role)?.toString()
        val stateDescription   = config.getOrNull(SemanticsProperties.StateDescription)
            ?: accessibleConfig?.getOrNull(SemanticsProperties.StateDescription)
        val selected           = config.getOrNull(SemanticsProperties.Selected) ?: false
        val disabled           = config.contains(SemanticsProperties.Disabled)
        val qaName             = config.getOrNull(QaNameKey)
        val hidden             = config.getOrNull(QaHiddenFromReportsKey) ?: false

        return InspectNode(
            id                 = "semantics:${root?.let(::rootId) ?: return null}:${this.id}",
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
            source             = NodeSource.SEMANTICS,
            hiddenFromReports  = hidden || inheritedHidden
        )
    }
}
