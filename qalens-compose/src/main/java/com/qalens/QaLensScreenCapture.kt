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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

internal object QaLensScreenCapture {

    private const val OVERLAY_TAG = "qalens_overlay_compose_view"
    private const val PROVIDER_SUFFIX = ".qalens.fileprovider"

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
        val decor = activity.window.decorView
        val overlay = if (manageOverlay) decor.findViewWithTag<View>(OVERLAY_TAG) else null
        overlay?.visibility = View.INVISIBLE
        val finish: (Bitmap?) -> Unit = { bmp -> overlay?.visibility = View.VISIBLE; onResult(bmp) }

        if (decor.width <= 0 || decor.height <= 0) { finish(null); return }
        val doCapture = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(activity.window, bmp, { result ->
                        finish(if (result == PixelCopy.SUCCESS) bmp else null)
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
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
        share: Boolean = true
    ) {
        val decor = activity.window.decorView
        val overlay = decor.findViewWithTag<View>(OVERLAY_TAG)

        overlay?.visibility = View.INVISIBLE

        // Annotate into a copy, then recycle the raw capture bitmap so it isn't leaked per shot.
        val annotateAndRecycle: (Bitmap) -> Bitmap = { raw ->
            val annotated = annotate(raw, nodes, selectedNode, activity)
            if (annotated !== raw) raw.recycle()
            annotated
        }
        val doCapture = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bmp, { result ->
                    overlay?.visibility = View.VISIBLE
                    if (result == PixelCopy.SUCCESS) deliver(activity, annotateAndRecycle(bmp), share) else bmp.recycle()
                }, Handler(Looper.getMainLooper()))
            } else {
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bmp))
                overlay?.visibility = View.VISIBLE
                deliver(activity, annotateAndRecycle(bmp), share)
            }
        }

        // Post so the overlay has one frame to disappear before capture fires.
        if (overlay != null) decor.post(doCapture) else doCapture.run()
    }

    /**
     * Saves the annotated screenshot into the system gallery (Pictures/QaLens via MediaStore — no
     * permission needed on API 29+) so it's always on the device, then optionally opens the share
     * sheet on top. Pre-29 falls back to the cache+share path only.
     */
    private fun saveToGallery(activity: Activity, bitmap: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val resolver = activity.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "qalens_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QaLens")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
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
            val lbl = (node.qaName ?: node.testTag)?.take(32)
                ?: if (isSelected) node.label.take(32) else null
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
        canvas.drawText(footer, 16f * density, fy + 34f * density, fp)

        return out
    }

    /** Always lands the shot somewhere durable (gallery + cache evidence record); share is optional. */
    private fun deliver(activity: Activity, bitmap: Bitmap, share: Boolean) {
        try {
            val savedToGallery = saveToGallery(activity, bitmap)

            val dir = File(activity.cacheDir, "qalens").also { it.mkdirs() }
            val file = File(dir, "qa_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }

            QaLens.recordScreenshot(file.absolutePath, available = true)
            // Visible confirmation — a log line nobody sees is not feedback. The toast shows over
            // the app even with the panel closed (the silent-save path's only signal).
            val note = if (savedToGallery) "📷 Saved to Photos · Pictures/QaLens"
                       else "📷 Screenshot captured (gallery needs Android 10+)"
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
        } catch (e: Exception) {
            QaLens.recordScreenshot(null, available = false, note = e.javaClass.simpleName)
            QaLens.log("Screenshot failed: ${e.message}")
        }
    }
}
