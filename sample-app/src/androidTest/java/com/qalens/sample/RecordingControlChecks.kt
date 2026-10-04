package com.qalens.sample

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Real Control Room buttons, including a consuming app that only uses QaLensRoot. */
internal class RecordingControlChecks(private val test: Instrumentation) {
    fun run(video: Boolean, manualRoot: Boolean) {
        if (manualRoot) removeAutoInstall()
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        test.waitForIdleSync()
        if (manualRoot) verifyManualRootClip()
        val root = File(test.targetContext.filesDir, "qalens/recordings")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        test.runOnMainSync { QaLens.configure { enabled = true; allowUnmaskedVideo = video } }
        test.startActivitySync(Intent(test.targetContext, QaLensControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // A rejected HD request must explain the host opt-in instead of silently jumping away.
        test.runOnMainSync { QaLens.configure { allowUnmaskedVideo = false } }
        click("●  Record HD Video (MediaProjection)")
        await(5000, "HD privacy opt-in failure was hidden") { findText("HD recording is disabled by this app’s privacy settings.", contains = true) != null }
        check(!QaLens.state.value.isRecording)
        test.runOnMainSync { QaLens.configure { enabled = false } }
        click("●  Record Session (frames, no permission)")
        await(5000, "Disabled SDK failure was hidden") { findText("QaLens is disabled in this app.", contains = true) != null }
        test.runOnMainSync { QaLens.configure { enabled = true; allowUnmaskedVideo = video } }
        click(if (video) "●  Record HD Video (MediaProjection)" else "●  Record Session (frames, no permission)")
        await(if (video) 90_000 else 15_000, "Control Room returned to the app without starting capture") {
            QaLens.state.value.isRecording
        }
        Thread.sleep(2500)
        RecordingClipChecks(test).markFromUi()
        Thread.sleep(800)
        check(QaLens.state.value.isRecording) { "Clip stopped the Control Room recording" }
        test.runOnMainSync { QaLens.stopRecording() }
        await(60_000, "Control Room capture did not save") { !QaLens.state.value.isSavingRecording }
        val files = root.listFiles()?.filter { it.extension == "sal" && it.name !in before }.orEmpty()
        check(files.count { it.name.startsWith("session_") } == 1)
        check(files.count { it.name.startsWith("clip_") } == 2)
        files.forEach { file -> ZipFile(file).use { zip ->
            val bytes = zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes() }
            val manifest = JSONObject(GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() })
            if (video) {
                check(zip.getEntry("video.mp4") != null) { "HD silently fell back to frames" }
                RecordingClipChecks(test).verifyVideo(zip)
            } else check(manifest.getJSONObject("frameIndex").length() > 0) { "No app frames captured" }
        } }
    }

    private fun verifyManualRootClip() {
        val root = File(test.targetContext.filesDir, "qalens/recordings")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        await(5000, "QaLensRoot did not establish its Activity lifecycle") { QaLens.state.value.isInstalled }
        test.runOnMainSync { QaLens.startRecording() }
        check(QaLens.state.value.isRecording)
        Thread.sleep(1500)
        // Exercise the exact old line-642 null-global-context path after the IO callback too.
        test.runOnMainSync {
            QaLens::class.java.getDeclaredField("appRef").apply { isAccessible = true }.set(QaLens, null)
            QaLens.saveRecentClip(10, "Null-context regression")
        }
        Thread.sleep(800)
        check(QaLens.state.value.isRecording)
        test.runOnMainSync { QaLens.stopRecording() }
        await(30_000, "Manual root recording/clip did not save") { !QaLens.state.value.isSavingRecording }
        val files = root.listFiles()?.filter { it.extension == "sal" && it.name !in before }.orEmpty()
        check(files.count { it.name.startsWith("session_") } == 1 && files.count { it.name.startsWith("clip_") } == 1)
        // Return through the real share sheet; background Activity launch restrictions otherwise
        // make a synthetic Application.startActivity unreliable on current Android versions.
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        await(5000, "Manual root did not resume after sharing") {
            QaLens::class.java.getDeclaredField("currentActivityRef").apply { isAccessible = true }
                .get(QaLens).let { (it as? java.lang.ref.WeakReference<*>)?.get() is MainActivity }
        }
    }

    /** Test fixture: simulate removal of Startup/manual Application.install before any host exists. */
    internal fun removeAutoInstall() = test.runOnMainSync {
        QaLens.configure { enabled = false }
        val type = Class.forName("com.qalens.QaLensActivityInstaller")
        val installer = type.getField("INSTANCE").get(null) as Application.ActivityLifecycleCallbacks
        (test.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(installer)
        type.getDeclaredField("installed").apply { isAccessible = true }.setBoolean(installer, false)
        // Newer installers distinguish callback registration from automatic activity injection.
        runCatching { type.getDeclaredField("callbacksRegistered").apply { isAccessible = true }.setBoolean(installer, false) }
        QaLens::class.java.getDeclaredField("appRef").apply { isAccessible = true }.set(QaLens, null)
        QaLens::class.java.getDeclaredField("currentActivityRef").apply { isAccessible = true }.set(QaLens, null)
        QaLens.configure { enabled = true; enableAutoInstall = false }
    }

    private fun click(label: String) {
        await(10_000, "Control Room button missing: $label") { findText(label) != null }
        var node = checkNotNull(findText(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findText(label: String, contains: Boolean = false): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty()
            if (if (contains) label in text else text == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return test.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun await(timeout: Long, message: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { message }
    }
}
