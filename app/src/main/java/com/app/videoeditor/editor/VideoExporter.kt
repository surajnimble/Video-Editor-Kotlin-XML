package com.app.videoeditor.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.app.videoeditor.core.EditorSticker
import com.app.videoeditor.core.EditorText
import com.app.videoeditor.core.OverlayItem
import com.app.videoeditor.widget.TextStyles
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class VideoExporter(private val context: Context) {

    fun interface Callback {
        fun onExportDone(success: Boolean, outputUri: Uri?, message: String?)
    }

    private val tag = "VideoExporter"
    private data class RenderedItem(val file: File, val x: Int, val y: Int)

    fun export(inputUri: Uri, videoWidth: Int, videoHeight: Int, items: List<OverlayItem>, callback: Callback) {
        Thread {
            try {
                val inputPath = prepareInput(inputUri)
                val rendered = renderItems(items, videoWidth, videoHeight)
                val output = outputFile()
                val command = buildCommand(inputPath, rendered, output)

                Log.d(tag, "Running FFmpeg: $command")
                val session = FFmpegKit.execute(command)

                if (ReturnCode.isSuccess(session.returnCode) && output.exists()) {
                    val galleryUri = saveToGallery(output)
                    output.delete()
                    callback.onExportDone(true, galleryUri, null)
                } else {
                    val logs = session.allLogs.joinToString("\n") { it.message }
                    Log.e(tag, "FFmpeg failed: $logs")
                    callback.onExportDone(false, null, "Export failed")
                }
            } catch (e: Exception) {
                Log.e(tag, "Export error", e)
                callback.onExportDone(false, null, e.message ?: "Export error")
            }
        }.start()
    }

    private fun prepareInput(uri: Uri): String {
        if (uri.scheme == "file" || uri.scheme == null) return uri.path ?: uri.toString()
        val input = File(context.cacheDir, "editor_input_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            input.outputStream().use { out -> ins.copyTo(out) }
        }
        return input.absolutePath
    }

    // FIX: ab dynamic-size bitmap return karta hai (text width + rotation ke hisaab se),
    // isliye position bhi us bitmap ke actual width/height se calculate hoti hai —
    // ab pixelSize/2 wala fixed/wrong offset nahi use hota, isliye cutting/misalignment fix.
    private fun renderItems(items: List<OverlayItem>, w: Int, h: Int): List<RenderedItem> {
        val dir = File(context.cacheDir, "editor_items").apply { if (!exists()) mkdirs(); listFiles()?.forEach { it.delete() } }
        val refDimension = min(w, h)
        val density = context.resources.displayMetrics.density

        return items.mapIndexed { index, item ->
            val pixelSize = (refDimension * item.size).roundToInt().coerceAtLeast(8)
            val bitmap = renderOverlayItemToBitmap(item, pixelSize, density)

            val file = File(dir, "item_$index.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

            val x = (item.centerX * w - bitmap.width / 2f).roundToInt()
            val y = (item.centerY * h - bitmap.height / 2f).roundToInt()
            bitmap.recycle()

            RenderedItem(file, x, y)
        }
    }

    private fun renderOverlayItemToBitmap(item: OverlayItem, pixelSize: Int, density: Float): Bitmap =
        when (item) {
            is EditorSticker -> renderStickerBitmap(item, pixelSize, density)
            is EditorText -> renderTextBitmap(item, pixelSize, density)
        }

    private fun renderStickerBitmap(item: EditorSticker, pixelSize: Int, density: Float): Bitmap {
        val padding = 24f * density
        // Rotate hone par square emoji ke corners bahar nikal jaate hain, isliye diagonal size use karo
        val diagonal = hypot(pixelSize.toFloat(), pixelSize.toFloat())
        val safeSize = (diagonal + padding).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = safeSize / 2f
        val cy = safeSize / 2f

        canvas.save()
        canvas.rotate(item.rotation, cx, cy)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = pixelSize.toFloat()
        }
        val baseline = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(item.emoji, cx, baseline, paint)

        canvas.restore()
        return bitmap
    }

    private fun renderTextBitmap(item: EditorText, pixelSize: Int, density: Float): Bitmap {
        val size = pixelSize.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            textSize = size
            typeface = TextStyles.byId(item.fontStyleId).typeface
        }

        val lines = item.text.split("\n")
        val lineWidths = lines.map { paint.measureText(it) }
        val fm = paint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val hPad = size * 0.67f
        val vPad = size * 0.25f
        val cornerRadius = size * 0.33f

        val blockWidth = (lineWidths.maxOrNull() ?: 0f) + hPad * 2
        val blockHeight = lineHeight * lines.size + vPad * 2

        val diagonal = hypot(blockWidth.toDouble(), blockHeight.toDouble()).toFloat()
        val safeSize = (diagonal + 24f * density).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = safeSize / 2f
        val cy = safeSize / 2f

        canvas.save()
        canvas.rotate(item.rotation, cx, cy)

        fun centerX(i: Int): Float = when (item.contentAlignment) {
            0 -> cx - blockWidth / 2f + hPad + lineWidths[i] / 2f   // LEFT
            2 -> cx + blockWidth / 2f - hPad - lineWidths[i] / 2f   // RIGHT
            else -> cx                                               // CENTER
        }
        fun centerY(i: Int): Float = cy - blockHeight / 2f + vPad + lineHeight * i + lineHeight / 2f

         if (Color.alpha(item.backgroundColor) > 0) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = item.backgroundColor }
            lines.forEachIndexed { i, line ->
                if (line.isBlank()) return@forEachIndexed
                val x = centerX(i); val y = centerY(i)
                canvas.drawRoundRect(
                    x - lineWidths[i] / 2f - hPad, y - lineHeight / 2f - vPad,
                    x + lineWidths[i] / 2f + hPad, y + lineHeight / 2f + vPad,
                    cornerRadius, cornerRadius, bgPaint
                )
            }
        }

        paint.color = item.color
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            canvas.drawText(line, centerX(i), centerY(i) - (fm.ascent + fm.descent) / 2f, paint)
        }

        canvas.restore()
        return bitmap
    }

    private fun buildCommand(inputPath: String, items: List<RenderedItem>, output: File): String {
        if (items.isEmpty()) return "-y -i \"$inputPath\" -c copy \"${output.absolutePath}\""

        val inputs = buildString {
            append("-y -i \"$inputPath\"")
            items.forEach { append(" -loop 1 -i \"${it.file.absolutePath}\"") }
        }

        val filters = mutableListOf<String>()
        var current = "[0:v]"
        items.forEachIndexed { index, s ->
            val nextLabel = if (index == items.size - 1) "vout" else "v${index + 1}"
            filters.add("$current[${index + 1}:v]overlay=x=${s.x}:y=${s.y}:eof_action=pass[$nextLabel]")
            current = "[$nextLabel]"
        }

        return buildString {
            append(inputs)
            append(" -filter_complex \"${filters.joinToString(";")}\"")
            append(" -map \"[vout]\" -map \"0:a?\" -c:v libopenh264 -b:v 4M -pix_fmt yuv420p -c:a aac -b:a 128k -shortest")
            append(" -y \"${output.absolutePath}\"")
        }
    }

    private fun outputFile(): File = File(context.cacheDir, "editor_${System.currentTimeMillis()}.mp4")

    private fun saveToGallery(output: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, output.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditorApp")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> output.inputStream().use { it.copyTo(out) } }
        return uri
    }
}