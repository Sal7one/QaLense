package com.qalens

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** SDK-owned Dialog/Popup content must never become a host root or a new inspector anchor. */
@Composable
internal fun ExcludeQaLensWindowFromInspection() {
    val view = LocalView.current
    // This view belongs to our window. Keep the marker through the dismissal frame; removing
    // it on composition disposal could briefly rediscover a closing SDK dialog as host content.
    SideEffect { view.tag = QaLensActivityInstaller.OVERLAY_TAG }
}

/** Process-local public window discovery. Never uses WindowManagerGlobal/hidden-field reflection. */
internal object QaLensInspectionWindows {
    var secureInspection by mutableStateOf(false)
        private set
    private val ids = WeakHashMap<View, Long>()
    private var nextId = 0L
    private val manualOwners = mutableMapOf<String, WeakReference<View>>()
    private var surface: Surface? = null
    private var failedAnchor: WeakReference<View>? = null
    private var syncing = false

    private class Surface(val activity: Activity, val anchor: View, val host: QaLensOverlayHost,
        val base: View?, val baseVisibility: Int?, val params: WindowManager.LayoutParams,
        val detachListener: View.OnAttachStateChangeListener)

    fun roots(activity: Activity?): List<View> {
        if (activity == null) return emptyList()
        val seen = IdentityHashMap<View, Boolean>()
        fun eligible(view: View) = view.isAttachedToWindow && view.isShown &&
            QaLensActivityInstaller.belongsToActivity(view, activity) && containsHostCompose(view)
        val automatic = if (Build.VERSION.SDK_INT >= 29) runCatching { WindowInspector.getGlobalWindowViews() }
            .getOrDefault(emptyList()).filter(::eligible) else emptyList()
        // Use inventory attachment order for extra windows. Explicit hooks cover pre-29 and
        // remain deduplicated on newer devices; both paths require the actual Activity owner.
        val explicit = QaLensActivityInstaller.inspectionRoots(activity).map { it.rootView }.filter(::eligible)
        return (listOf(activity.window.decorView) + automatic + explicit.filter { it !in automatic })
            .filter { it.isAttachedToWindow && it.isShown && seen.put(it, true) == null }
    }

    private fun containsHostCompose(view: View): Boolean {
        if (view.tag == QaLensActivityInstaller.OVERLAY_TAG) return false
        if (view is androidx.compose.ui.node.RootForTest) return true
        return view is ViewGroup && (0 until view.childCount).any { containsHostCompose(view.getChildAt(it)) }
    }

    fun rememberManual(id: String, view: View) { manualOwners[id] = WeakReference(view) }
    fun forgetManual(id: String) { manualOwners.remove(id) }
    private fun owner(id: String) = QaLensActivityInstaller.inspectionWindowFor(id) ?: manualOwners[id]?.get()?.rootView
    private fun id(view: View) = "window:${ids.getOrPut(view) { ++nextId }}"
    fun windowId(nodeId: String): String? = owner(nodeId)?.let(::id)

    private fun bounds(activity: Activity, view: View): QaRect {
        val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
        val source = IntArray(2).also { view.getLocationOnScreen(it) }
        return QaRect(source[0] - origin[0], source[1] - origin[1],
            source[0] - origin[0] + view.width, source[1] - origin[1] + view.height)
    }

    fun snapshot(activity: Activity): List<Map<String, Any?>> = roots(activity).mapIndexed { order, view ->
        val b = bounds(activity, view)
        mapOf("id" to id(view), "order" to order, "kind" to if (view === activity.window.decorView) "activity" else "window",
            "bounds" to mapOf("left" to b.left, "top" to b.top, "right" to b.right, "bottom" to b.bottom))
    }

    /** A small background node must not steal a hit from a dialog over it. */
    fun hit(nodes: List<InspectNode>, x: Float, y: Float): InspectNode? {
        val activity = QaLens.currentActivity ?: return null
        val window = roots(activity).lastOrNull { bounds(activity, it).contains(x, y) } ?: return null
        return nodes.filter { it.bounds.contains(x, y) && owner(it.id) === window }
            .minWithOrNull(compareBy<InspectNode> { it.bounds.width.toLong() * it.bounds.height }
                // Compose's window/layout containers can have exactly the same bounds as the
                // component. Prefer an identifiable/actionable node instead of the empty root.
                .thenByDescending { it.testTag != null }
                .thenByDescending { it.isClickable || it.isFocusable }
                .thenByDescending { it.hasHumanLabel })
    }

    fun overlayViews(activity: Activity): List<View> = listOfNotNull(
        activity.window.decorView.findViewWithTag<View>(QaLensActivityInstaller.OVERLAY_TAG),
        surface?.takeIf { it.activity === activity }?.host)

    fun visibilityBeforeInspection(view: View): Int = surface?.takeIf { it.base === view }?.baseVisibility
        ?: QaLensScreenCapture.visibilityBeforeCapture(view)

    /** Active inspection gets an owned, nonfocusable application window. No overlay permission. */
    fun sync(activity: Activity) {
        if (syncing) return
        syncing = true
        try {
            val state = QaLens.state.value
            val allowed = QaLens.config.value.enabled && state.overlayEnabled && !state.isRecording && !state.isWatchMode &&
                (state.isInspectMode || state.isTagMode || state.isPanelOpen) && QaLensActivityInstaller.isResumed(activity)
            val windows = if (allowed) roots(activity) else emptyList()
            val anchor = windows.lastOrNull()?.takeUnless { it === activity.window.decorView }
            val secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 || windows.any {
                ((it.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
            secureInspection = secure
            if (anchor == null) { dismiss(); failedAnchor = null; return }
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0)
            val decor = activity.window.decorView
            val location = IntArray(2).also(decor::getLocationOnScreen)
            val existing = surface
            if (existing?.activity === activity && existing.anchor === anchor) {
                val p = existing.params
                if (p.width != decor.width || p.height != decor.height || p.x != location[0] || p.y != location[1] || p.flags != flags) {
                    p.width = decor.width; p.height = decor.height; p.x = location[0]; p.y = location[1]
                    p.flags = flags
                    activity.windowManager.updateViewLayout(existing.host, p)
                }
                return
            }
            dismiss()
            if (failedAnchor?.get() === anchor || decor.width <= 0 || decor.height <= 0) return
            val base = decor.findViewWithTag<View>(QaLensActivityInstaller.OVERLAY_TAG)
            val host = QaLensOverlayHost(activity) {
                anchor.takeIf { it.isAttachedToWindow }?.findViewById<View>(android.R.id.content) ?: anchor.takeIf { it.isAttachedToWindow }
            }.apply {
                tag = QaLensActivityInstaller.OVERLAY_TAG
                setViewTreeLifecycleOwner(decor.findViewTreeLifecycleOwner())
                setViewTreeViewModelStoreOwner(decor.findViewTreeViewModelStoreOwner())
                setViewTreeSavedStateRegistryOwner(decor.findViewTreeSavedStateRegistryOwner())
                addView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent { QaLensOverlay() }
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val params = WindowManager.LayoutParams(decor.width, decor.height, WindowManager.LayoutParams.TYPE_APPLICATION,
                flags,
                PixelFormat.TRANSLUCENT).apply {
                // Activity.windowManager supplies its application token. Avoid nesting underneath
                // a Popup: Android rejects sub-window parent tokens. Ownership still follows the
                // anchor's attachment and the host Activity lifecycle.
                gravity = Gravity.TOP or Gravity.LEFT
                x = location[0]; y = location[1]
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                title = "QaLens inspector"
            }
            val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (surface?.anchor === view) dismiss(activity)
                    QaLens.scheduleInspection()
                }
            }
            try {
                activity.windowManager.addView(host, params)
                anchor.addOnAttachStateChangeListener(detach)
                surface = Surface(activity, anchor, host, base, base?.let(QaLensScreenCapture::visibilityBeforeCapture), params, detach)
                base?.visibility = View.INVISIBLE
                failedAnchor = null
            } catch (failure: RuntimeException) {
                if (host.isAttachedToWindow) runCatching { activity.windowManager.removeViewImmediate(host) }
                failedAnchor = WeakReference(anchor)
                QaLens.pushError(ErrorKind.OTHER, "Dialog inspector could not open (${failure.javaClass.simpleName}); return to the app and try inspection again.")
            }
        } catch (_: RuntimeException) { dismiss(activity) }
        finally { syncing = false }
    }

    fun dismiss(activity: Activity? = null) {
        if (activity != null && failedAnchor?.get()?.let { QaLensActivityInstaller.belongsToActivity(it, activity) } == true)
            failedAnchor = null
        val old = surface ?: return
        if (activity != null && old.activity !== activity) return
        surface = null
        old.anchor.removeOnAttachStateChangeListener(old.detachListener)
        runCatching { old.activity.windowManager.removeViewImmediate(old.host) }
        if (!QaLens.state.value.isRecording && QaLens.config.value.enabled && QaLens.state.value.overlayEnabled) {
            old.baseVisibility?.let { visibility -> old.base?.let { view ->
                view.visibility = if (QaLensScreenCapture.isHiddenForCapture(view)) View.INVISIBLE else visibility
            } }
        }
    }
}
