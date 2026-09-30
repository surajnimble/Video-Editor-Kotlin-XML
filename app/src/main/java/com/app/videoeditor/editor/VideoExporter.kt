package com.app.videoeditor.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Rect
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

    // FIX: ab do tarah ke overlay ho sakte hain -- static Image (loop 1 se
    // poori video par loop hoti hai) aur animated Clip. Clip ab ek WEBM file
    // nahi balki seedha PNG-frames ka SEQUENCE hai (f_%03d.png) -- har frame
    // apni RGBA transparency ke saath, isliye koi alpha-codec dependency
    // nahi. VP9/WEBM alpha intermediate step hata diya kyunki device par
    // alpha sahi se preserve nahi ho raha tha (background black aa raha tha).
    private sealed class RenderedItem(val x: Int, val y: Int) {
        class Image(val file: File, x: Int, y: Int) : RenderedItem(x, y)
        class Clip(val framePattern: String, val fps: Int, x: Int, y: Int) : RenderedItem(x, y)
    }

    private data class AnimatedClipInfo(val framePattern: String, val fps: Int, val width: Int, val height: Int)

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

    // FIX: ek item ka render fail hone par poori export process crash nahi
    // honi chahiye -- us item ko skip karo, baaki items export hote rahein.
    // Dir ko deleteRecursively+mkdirs se saaf karte hain (shallow forEach
    // delete purani gif_frames_X/ subdirectories ko clean nahi kar pata tha).
    private fun renderItems(items: List<OverlayItem>, w: Int, h: Int): List<RenderedItem> {
        val dir = File(context.cacheDir, "editor_items").apply { deleteRecursively(); mkdirs() }
        val refDimension = min(w, h)
        val density = context.resources.displayMetrics.density

        return items.mapIndexedNotNull { index, item ->
            try {
                val pixelSize = (refDimension * item.size).roundToInt().coerceAtLeast(8)

                // FIX: asli animated GIF ho to alag path -- animated PNG-frame-sequence banao.
                if (item is EditorSticker && item.imageUri != null) {
                    val clip = renderAnimatedStickerClip(item, pixelSize, dir, index)
                    if (clip != null) {
                        val x = (item.centerX * w - clip.width / 2f).roundToInt()
                        val y = (item.centerY * h - clip.height / 2f).roundToInt()
                        return@mapIndexedNotNull RenderedItem.Clip(clip.framePattern, clip.fps, x, y)
                    }
                }

                val bitmap = renderOverlayItemToBitmap(item, pixelSize, density)
                val file = File(dir, "item_$index.png")
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                val x = (item.centerX * w - bitmap.width / 2f).roundToInt()
                val y = (item.centerY * h - bitmap.height / 2f).roundToInt()
                bitmap.recycle()
                RenderedItem.Image(file, x, y)
            } catch (e: Exception) {
                Log.e(tag, "Skipping item $index ($item) -- render failed", e)
                null
            }
        }
    }

    // FIX: real animated GIF sticker -- saare frames nikaal ke RGBA PNG
    // sequence me save karte hain (f_%03d.png). Koi intermediate video-codec
    // encode NAHI hota -- transparency wahi reliable RGBA-PNG path se aati
    // hai jo static stickers ke liye already sahi kaam kar raha tha.
    // Static image (single-frame) ya emoji stickers ke liye yeh null return
    // karta hai, aur woh purane static-bitmap path se hi render hote hain.
    // LIMITATION: is path me sticker rotation apply nahi hoti (sirf position
    // + size) -- rotated + animated combo abhi support nahi hai.
    @Suppress("DEPRECATION")
    private fun renderAnimatedStickerClip(item: EditorSticker, pixelSize: Int, dir: File, index: Int): AnimatedClipInfo? {
        val uri = Uri.parse(item.imageUri)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return null

        val movie = try { Movie.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
        // duration <= 0 matlab single-frame (static) image -- animate karne
        // ki zaroorat nahi, purane static path se render hoga.
        if (movie == null || movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0) return null

        val framesDir = File(dir, "gif_frames_$index").apply { mkdirs() }
        val fps = 12
        val frameIntervalMs = 1000 / fps
        val maxFrames = 60 // export time/size bounded rakhne ke liye cap
        val frameCount = (movie.duration() / frameIntervalMs).coerceAtMost(maxFrames).coerceAtLeast(1)

        val scale = pixelSize.toFloat() / maxOf(movie.width(), movie.height())
        val dstW = (movie.width() * scale).roundToInt().coerceAtLeast(2)
        val dstH = (movie.height() * scale).roundToInt().coerceAtLeast(2)

        for (i in 0 until frameCount) {
            val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888) // transparent by default
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            movie.setTime(i * frameIntervalMs)
            movie.draw(canvas, 0f, 0f)
            FileOutputStream(File(framesDir, "f_%03d.png".format(i))).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
        }

        return AnimatedClipInfo("${framesDir.absolutePath}/f_%03d.png", fps, dstW, dstH)
    }

    private fun renderOverlayItemToBitmap(item: OverlayItem, pixelSize: Int, density: Float): Bitmap =
        when (item) {
            is EditorSticker -> renderStickerBitmap(item, pixelSize, density)
            is EditorText -> renderTextBitmap(item, pixelSize, density)
        }

    private fun renderStickerBitmap(item: EditorSticker, pixelSize: Int, density: Float): Bitmap =
        if (item.imageUri != null) renderImageStickerBitmap(item, pixelSize, density)
        else renderEmojiStickerBitmap(item, pixelSize, density)

    private fun renderEmojiStickerBitmap(item: EditorSticker, pixelSize: Int, density: Float): Bitmap {
        val padding = 24f * density
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

    // NOTE: yeh sirf tab chalta hai jab renderAnimatedStickerClip() null de
    // (matlab static image hai, GIF nahi) -- animated GIF ke liye upar wala
    // renderAnimatedStickerClip() use hota hai.
    private fun renderImageStickerBitmap(item: EditorSticker, pixelSize: Int, density: Float): Bitmap {
        val uri = Uri.parse(item.imageUri)
        val srcBitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Sticker image decode failed for uri=$uri", e)
            null
        } ?: return Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888) // decode fail -> blank

        val scale = pixelSize.toFloat() / maxOf(srcBitmap.width, srcBitmap.height)
        val dstW = (srcBitmap.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (srcBitmap.height * scale).roundToInt().coerceAtLeast(1)

        val diagonal = hypot(dstW.toDouble(), dstH.toDouble()).toFloat()
        val safeSize = (diagonal + 24f * density).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = safeSize / 2f
        val cy = safeSize / 2f

        canvas.save()
        canvas.rotate(item.rotation, cx, cy)
        val dstRect = Rect(
            (cx - dstW / 2f).roundToInt(), (cy - dstH / 2f).roundToInt(),
            (cx + dstW / 2f).roundToInt(), (cy + dstH / 2f).roundToInt()
        )
        val drawSrc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && srcBitmap.config == Bitmap.Config.HARDWARE) {
            srcBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            srcBitmap
        }
        canvas.drawBitmap(drawSrc, null, dstRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        if (drawSrc !== srcBitmap) drawSrc.recycle()
        srcBitmap.recycle()
        return bitmap
    }

    private fun buildCommand(inputPath: String, items: List<RenderedItem>, output: File): String {
        if (items.isEmpty()) return "-y -i \"$inputPath\" -c copy \"${output.absolutePath}\""

        val inputs = buildString {
            append("-y -i \"$inputPath\"")
            items.forEach { item ->
                when (item) {
                    // Static image: -loop 1 -i single.png (poori video par same frame loop)
                    is RenderedItem.Image -> append(" -loop 1 -i \"${item.file.absolutePath}\"")
                    // FIX: animated GIF ab PNG-frame-sequence se -- -loop 1 yahan
                    // ek image2-sequence input par POORI sequence ko repeatedly
                    // loop karta hai (ek single frame ko repeat nahi), isliye
                    // asli animation milti hai, alpha bhi RGBA-PNG se reliable hai.
                    is RenderedItem.Clip -> append(" -framerate ${item.fps} -loop 1 -i \"${item.framePattern}\"")
                }
            }
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