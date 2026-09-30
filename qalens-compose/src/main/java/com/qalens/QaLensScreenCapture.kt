package com.qalens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

internal object QaLensScreenCapture {

    private const val OVERLAY_TAG = "qalens_overlay_compose_view"
    private const val PROVIDER_SUFFIX = ".qalens.fileprovider"

    private val deliveryWorker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "qalens-screenshot").apply { isDaemon = true } }
    )

    /** Mask explicitly hidden nodes, password fields and text recognized by configured redaction.
     * This is not OCR: custom Canvas/View content must use FLAG_SECURE or an enclosing hidden node.
     */
    private fun maskBounds(activity: Activity): List<androidx.compose.ui.geometry.Rect>? = runCatching {
        val cfg = QaLens.config.value
        QaLensActivityInstaller.captureSemanticsNodes(activity).mapNotNull { node ->
            val c = node.config
            val text = (c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text) +
                c.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).joinToString(" ")
            if (c.contains(SemanticsProperties.Password) || c.getOrNull(QaHiddenFromReportsKey) == true || cfg.redact(text) != text)
                node.boundsInWindow else null
        }
    }.getOrNull()

    private fun mask(bitmap: Bitmap, bounds: List<androidx.compose.ui.geometry.Rect>) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        bounds.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, paint) }
    }

    /** Show/hide the QaLens overlay view (bubble + panel) on the given activity. */
    fun setOverlayVisible(activity: Activity, visible: Boolean) {
        val overlay = activity.window.decorView.findViewWithTag<View>(OVERLAY_TAG)
        overlay?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Captures a single raw frame of the current window for the session recorder.
     *
     * [manageOverlay] = true hides the overlay just for this capture (used for one-off screenshots).
     * The recorder passes false because it hides the overlay once for the whole session — toggling
     * per frame would make the bubble flicker. Returns null on failure (zero-size / FLAG_SECURE).
     */
    fun captureFrame(activity: Activity, manageOverlay: Boolean = true, onResult: (Bitmap?) -> Unit) {
        if (!QaLens.config.value.enabled || activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            onResult(null); return
        }
        val epoch = QaLens.captureEpoch
        val before = maskBounds(activity) ?: run { onResult(null); return }
        val decor = activity.window.decorView
        val overlay = if (manageOverlay) decor.findViewWithTag<View>(OVERLAY_TAG) else null
        val previousVisibility = overlay?.visibility
        overlay?.visibility = View.INVISIBLE
        val finish: (Bitmap?) -> Unit = { bmp ->
            if (previousVisibility != null) overlay?.visibility = previousVisibility
            val after = if (bmp != null) maskBounds(activity) else emptyList()
            if (bmp != null && (!QaLens.config.value.enabled || epoch != QaLens.captureEpoch ||
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 || after == null)) {
                bmp.recycle()
                onResult(null)
            } else {
                if (bmp != null) mask(bmp, before + after.orEmpty())
                onResult(bmp)
            }
        }

        if (decor.width <= 0 || decor.height <= 0) { finish(null); return }
        val doCapture = Runnable {
            if (!QaLens.config.value.enabled || epoch != QaLens.captureEpoch || decor.width <= 0 || decor.height <= 0) {
                finish(null); return@Runnable
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(activity.window, bmp, { result ->
                        if (result == PixelCopy.SUCCESS) finish(bmp)
                        else { bmp.recycle(); finish(null) }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    bmp.recycle()
                    finish(null)
                }
            } else {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bmp))
                finish(bmp)
            }
        }
        // PixelCopy snapshots the last *composited* frame, so a same-pass visibility toggle would
        // still capture the overlay (e.g. the in-window REC chip). Post so the hidden overlay gets
        // one frame to leave the surface — same pattern captureAndShare has always used.
        if (overlay != null) decor.post(doCapture) else doCapture.run()
    }

    fun captureAndShare(
        activity: Activity,
        nodes: List<InspectNode>,
        selectedNode: InspectNode? = null,
        share: Boolean = true,
        onComplete: (Boolean) -> Unit = {}
    ) {
        captureFrame(activity) { raw ->
            if (raw == null) {
                QaLens.pushError(ErrorKind.SCREENSHOT, "Screenshot unavailable; the window may be secure or not ready.")
                onComplete(false)
                return@captureFrame
            }
            val epoch = QaLens.captureEpoch
            var annotated: Bitmap? = null
            try {
                annotated = annotate(raw, nodes, selectedNode, activity)
                val output = annotated
                deliveryWorker.execute {
                    try {
                        if (QaLens.config.value.enabled && epoch == QaLens.captureEpoch) deliver(activity, output, share, epoch, onComplete)
                        else QaLens.onMain { onComplete(false) }
                    } finally { output.recycle() }
                }
                annotated = null // worker owns this bitmap
            } catch (e: Exception) {
                QaLens.pushError(ErrorKind.SCREENSHOT, "Screenshot failed: ${e.message}")
                onComplete(false)
            } finally {
                if (annotated !== raw) annotated?.recycle()
                raw.recycle()
            }
        }
    }

    /**
     * Explicit gallery opt-in only. Delete partial or revoked exports before publishing them.
     */
    private fun saveToGallery(activity: Activity, bitmap: Bitmap, epoch: Long): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = activity.contentResolver
        var uri: android.net.Uri? = null
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "qalens_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QaLens")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val destination = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            uri = destination
            requireNotNull(resolver.openOutputStream(destination)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 95, it)) }
            check(QaLens.config.value.enabled && epoch == QaLens.captureEpoch && QaLens.config.value.saveScreenshotsToGallery)
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(destination, values, null, null)
            destination
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    private fun annotate(src: Bitmap, nodes: List<InspectNode>, selectedNode: InspectNode?, activity: Activity): Bitmap {
        val density = activity.resources.displayMetrics.density
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val green = Color.argb(230, 0, 200, 83)
        val red = Color.argb(230, 229, 57, 53)
        val amber = Color.argb(255, 255, 193, 7)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        val labelBg = Paint().apply { color = Color.argb(204, 0, 0, 0) }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
        }

        // Draw tagged, QA-named, and warning nodes. Selected node is emphasised last (on top).
        val toDraw = nodes.filter {
            it.testTag != null || it.qaName != null || it.warnings.isNotEmpty()
        }
        (toDraw + listOfNotNull(selectedNode)).distinctBy { it.id }.forEach { node ->
            val isSelected = node.id == selectedNode?.id
            val hasWarning = node.warnings.isNotEmpty()
            boxPaint.color = when {
                isSelected -> amber
                hasWarning -> red
                else       -> green
            }
            boxPaint.strokeWidth = if (isSelected) 4f * density else 2f * density
            canvas.drawRect(
                node.bounds.left.toFloat(), node.bounds.top.toFloat(),
                node.bounds.right.toFloat(), node.bounds.bottom.toFloat(), boxPaint
            )
            if (hasWarning) {
                canvas.drawCircle(node.bounds.right.toFloat(), node.bounds.top.toFloat(), 6f * density, dotPaint)
            }
            // Label selected + tagged/named nodes (skip pure-warning nodes to reduce clutter).
            val lbl = ((node.qaName ?: node.testTag)
                ?: if (isSelected) node.label else null)?.let { QaLens.config.value.redact(it).take(32) }
            if (lbl != null) {
                val tw = labelText.measureText(lbl)
                val lx = node.bounds.left.toFloat()
                val ly = (node.bounds.top - 4f * density).coerceAtLeast(20f * density)
                canvas.drawRect(lx, ly - 22f, lx + tw + 8f, ly + 4f, labelBg)
                canvas.drawText(lbl, lx + 4f, ly, labelText)
            }
        }

        // Footer: screen name + device + version
        val footerH = (52 * density).toInt()
        val fy = out.height - footerH
        canvas.drawRect(0f, fy.toFloat(), out.width.toFloat(), out.height.toFloat(),
            Paint().apply { color = Color.argb(230, 17, 24, 39) })
        val state = QaLens.state.value
        val footer = buildString {
            append(state.screen.displayName)
            append("  ·  ")
            append("${state.device.appName} ${state.device.appVersion}")
            append("  ·  ")
            append("${state.device.deviceModel} / Android ${state.device.androidVersion}")
        }
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
        }
        canvas.drawText(QaLens.config.value.redact(footer), 16f * density, fy + 34f * density, fp)

        return out
    }

    /** Save to private cache; gallery export and sharing require explicit opt-ins. */
    private fun deliver(activity: Activity, bitmap: Bitmap, share: Boolean, epoch: Long, onComplete: (Boolean) -> Unit) {
        var galleryUri: android.net.Uri? = null
        try {
            galleryUri = if (QaLens.config.value.saveScreenshotsToGallery) saveToGallery(activity, bitmap, epoch) else null
            val savedToGallery = galleryUri != null

            val dir = File(activity.cacheDir, "qalens").also { it.mkdirs() }
            val file = File(dir, "qa_${System.currentTimeMillis()}.png")
            val temporary = File(dir, ".${file.name}.tmp")
            try {
                FileOutputStream(temporary).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 95, it)) }
                check(temporary.renameTo(file)) { "Could not publish screenshot" }
            } finally { temporary.delete() }

            QaLens.onMain {
                if (!QaLens.config.value.enabled || epoch != QaLens.captureEpoch) {
                    Thread({
                        file.delete()
                        galleryUri?.let { runCatching { activity.contentResolver.delete(it, null, null) } }
                    }, "qalens-screenshot-cleanup").apply { isDaemon = true; start() }
                    onComplete(false); return@onMain
                }
                try {
                    QaLens.recordScreenshot(file.absolutePath, available = true)
                    val note = if (savedToGallery) "📷 Saved to Photos · Pictures/QaLens"
                               else "📷 Screenshot saved in app cache"
                    android.widget.Toast.makeText(activity, note, android.widget.Toast.LENGTH_SHORT).show()
                    QaLens.log(note)
                    if (share) {
                        val uri = FileProvider.getUriForFile(
                            activity, activity.packageName + PROVIDER_SUFFIX, file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, QaLens.buildJiraReport())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(intent, "Share QA Evidence"))
                    }
                    onComplete(true)
                } catch (failure: Exception) {
                    QaLens.pushError(ErrorKind.SCREENSHOT, "Screenshot sharing failed: ${failure.message}")
                    onComplete(false)
                }
            }

        } catch (e: Exception) {
            galleryUri?.let { runCatching { activity.contentResolver.delete(it, null, null) } }
            QaLens.onMain {
                QaLens.recordScreenshot(null, available = false, note = e.javaClass.simpleName)
                QaLens.log("Screenshot failed: ${e.message}")
                onComplete(false)
            }
        }
    }
}
