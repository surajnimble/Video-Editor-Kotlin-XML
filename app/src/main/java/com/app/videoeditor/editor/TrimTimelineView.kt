package com.app.videoeditor.trimmer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.app.videoeditor.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Generic start/end/playhead timeline -- video ya audio dono ke liye kaam
 * karta hai. loadFrames(uri) na call karo (audio ke liye zaroorat nahi), tab
 * yeh khud placeholder bars draw kar deta hai.
 */
class TrimTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface TimelineListener {
        fun onTrimDragStart()
        fun onTrimChanged(startMs: Long, endMs: Long)
        fun onTrimDragStop()
        fun onPlayheadMoved(ms: Long)
    }

    var listener: TimelineListener? = null

    /* =============== config =============== */
    private var frameColor = Color.parseColor("#E3E3E3")
    private var frameCorner = dp(6f)
    private var frameStrokeH = dp(5f)
    private var frameStrokeV = dp(5f)
    private var innerColor = Color.parseColor("#06030C")

    private var stripHeight = dp(45f)
    private var thumbSpacing = dp(3f)
    private var thumbCorner = 0f
    private var thumbML = 0f; private var thumbMT = 0f; private var thumbMR = 0f; private var thumbMB = 0f
    private var touchOffset = 0f

    private var leftBmp: Bitmap? = null
    private var rightBmp: Bitmap? = null
    private var handleColor = Color.parseColor("#9D9D9D")
    private var handleW = dp(15f)
    private var handleH = dp(60f)
    private var handleCorner = dp(4f)
    private var handleMarginStart = 0f
    private var handleMarginEnd = 0f

    private var playheadBmp: Bitmap? = null
    private var playheadColor = Color.WHITE
    private var playheadW = dp(10f)
    private var playheadH = dp(80f)
    private var playheadCorner = dp(10f)
    private var showPlayhead = true

    private var showDim = true
    private var dimColor = Color.parseColor("#666943AB")

    private var showBubble = true
    private var bubbleColor = Color.parseColor("#D9D9D9")
    private var bubbleTextColor = Color.parseColor("#000000")
    private var bubbleTextSize = dp(14f)
    private var bubbleCorner = dp(8f)
    private var bubbleStrokeW = 0f
    private var bubbleStrokeColor = Color.BLACK
    private var bubbleMarginY = 0f

    private var minTrimMs = 3000L

    /* =============== state =============== */
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var currentMs = 0L
    private var frames: List<Bitmap> = emptyList()
    private var waveform: FloatArray = FloatArray(0)
    private var loadThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val NONE = -1; private val LEFT = 0; private val RIGHT = 1; private val PLAYHEAD = 2
    private var dragTarget = NONE

    private var outerL = 0f; private var outerR = 0f; private var outerT = 0f; private var outerB = 0f
    private var innerL = 0f; private var innerR = 0f; private var stripT = 0f; private var stripB = 0f

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2438") }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9D9D9D") }
    private val rect = RectF()
    private val outerPath = Path()
    private val innerPath = Path()
    private val framePath = Path()
    private val clipPath = Path()
    private val arrowPath = Path()

    init {
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.TrimTimelineView)
            applyAttrs(ta)
            ta.recycle()
        }
    }

    fun applyAttrs(ta: TypedArray) {
        with(ta) {
            frameColor = getColor(R.styleable.TrimTimelineView_ttFrameColor, frameColor)
            frameCorner = getDimension(R.styleable.TrimTimelineView_ttFrameCornerRadius, frameCorner)
            frameStrokeH = getDimension(R.styleable.TrimTimelineView_ttFrameStrokeWidthH, frameStrokeH)
            frameStrokeV = getDimension(R.styleable.TrimTimelineView_ttFrameStrokeWidthV, frameStrokeV)
            innerColor = getColor(R.styleable.TrimTimelineView_ttInnerColor, innerColor)

            stripHeight = getDimension(R.styleable.TrimTimelineView_ttStripHeight, stripHeight)
            thumbSpacing = getDimension(R.styleable.TrimTimelineView_ttThumbSpacing, thumbSpacing)
            thumbCorner = getDimension(R.styleable.TrimTimelineView_ttThumbCornerRadius, thumbCorner)
            thumbML = getDimension(R.styleable.TrimTimelineView_ttThumbMarginLeft, thumbML)
            thumbMT = getDimension(R.styleable.TrimTimelineView_ttThumbMarginTop, thumbMT)
            thumbMR = getDimension(R.styleable.TrimTimelineView_ttThumbMarginRight, thumbMR)
            thumbMB = getDimension(R.styleable.TrimTimelineView_ttThumbMarginBottom, thumbMB)

            handleW = getDimension(R.styleable.TrimTimelineView_ttHandleWidth, handleW)
            handleH = getDimension(R.styleable.TrimTimelineView_ttHandleHeight, handleH)
            handleCorner = getDimension(R.styleable.TrimTimelineView_ttHandleCornerRadius, handleCorner)
            handleColor = getColor(R.styleable.TrimTimelineView_ttHandleColor, handleColor)
            handleMarginStart = getDimension(R.styleable.TrimTimelineView_ttHandleMarginStart, handleMarginStart)
            handleMarginEnd = getDimension(R.styleable.TrimTimelineView_ttHandleMarginEnd, handleMarginEnd)
            getDrawable(R.styleable.TrimTimelineView_ttLeftHandle)?.let {
                leftBmp = drawableToBitmap(it, handleW.toInt(), handleH.toInt())
            }
            getDrawable(R.styleable.TrimTimelineView_ttRightHandle)?.let {
                rightBmp = drawableToBitmap(it, handleW.toInt(), handleH.toInt())
            }

            playheadW = getDimension(R.styleable.TrimTimelineView_ttPlayheadWidth, playheadW)
            playheadH = getDimension(R.styleable.TrimTimelineView_ttPlayheadHeight, playheadH)
            playheadCorner = getDimension(R.styleable.TrimTimelineView_ttPlayheadCornerRadius, playheadCorner)
            playheadColor = getColor(R.styleable.TrimTimelineView_ttPlayheadColor, playheadColor)
            showPlayhead = getBoolean(R.styleable.TrimTimelineView_ttShowPlayhead, showPlayhead)
            getDrawable(R.styleable.TrimTimelineView_ttPlayheadIcon)?.let {
                playheadBmp = drawableToBitmap(it, playheadW.toInt(), playheadH.toInt())
            }

            showDim = getBoolean(R.styleable.TrimTimelineView_ttShowDim, showDim)
            dimColor = getColor(R.styleable.TrimTimelineView_ttDimColor, dimColor)

            showBubble = getBoolean(R.styleable.TrimTimelineView_ttBubbleShow, showBubble)
            bubbleColor = getColor(R.styleable.TrimTimelineView_ttBubbleColor, bubbleColor)
            bubbleTextColor = getColor(R.styleable.TrimTimelineView_ttBubbleTextColor, bubbleTextColor)
            bubbleTextSize = getDimension(R.styleable.TrimTimelineView_ttBubbleTextSize, bubbleTextSize)
            bubbleCorner = getDimension(R.styleable.TrimTimelineView_ttBubbleCornerRadius, bubbleCorner)
            bubbleStrokeW = getDimension(R.styleable.TrimTimelineView_ttBubbleStrokeWidth, bubbleStrokeW)
            bubbleStrokeColor = getColor(R.styleable.TrimTimelineView_ttBubbleStrokeColor, bubbleStrokeColor)
            bubbleMarginY = getDimension(R.styleable.TrimTimelineView_ttBubbleMarginY, bubbleMarginY)

            minTrimMs = (getFloat(R.styleable.TrimTimelineView_ttMinTrimSeconds, 3f) * 1000).toLong()
        }
        bubbleTextPaint.textSize = bubbleTextSize
        bubbleTextPaint.color = bubbleTextColor
        requestLayout()
        invalidate()
    }

    /* =============== public API =============== */
    fun setDuration(ms: Long) {
        durationMs = ms
        if (endMs == 0L || endMs > ms) { startMs = 0; endMs = ms }
        invalidate()
    }
    fun setTrimRange(start: Long, end: Long) {
        startMs = start.coerceIn(0, endMs)
        endMs = end.coerceIn(startMs, durationMs)
        invalidate()
    }
    fun getStartMs() = startMs
    fun getEndMs() = endMs
    fun updatePlayhead(ms: Long) { currentMs = ms; invalidate() }
    fun setPlayheadVisible(visible: Boolean) { showPlayhead = visible; invalidate() }
    fun setMinTrimSeconds(sec: Float) { minTrimMs = (sec * 1000).toLong() }

    fun setLeftHandle(resId: Int) {
        leftBmp = drawableToBitmap(ContextCompat.getDrawable(context, resId), handleW.toInt(), handleH.toInt())
        invalidate()
    }
    fun setRightHandle(resId: Int) {
        rightBmp = drawableToBitmap(ContextCompat.getDrawable(context, resId), handleW.toInt(), handleH.toInt())
        invalidate()
    }
    fun setPlayheadIcon(resId: Int) {
        playheadBmp = drawableToBitmap(ContextCompat.getDrawable(context, resId), playheadW.toInt(), playheadH.toInt())
        invalidate()
    }

    fun loadFrames(uri: Uri) {
        loadThread?.interrupt()
        val appContext = context.applicationContext
        loadThread = Thread {
            var mmr: MediaMetadataRetriever? = null
            try {
                mmr = MediaMetadataRetriever()
                mmr.setDataSource(appContext, uri)
                val total = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val first = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (first != null && total > 0) {
                    val viewW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val stripW = viewW - frameStrokeV.toInt() * 2 - thumbML.toInt() - thumbMR.toInt()
                    val count = 8
                    val cellW = (stripW - thumbSpacing.toInt() * (count - 1)) / count
                    val cellH = stripHeight.toInt()
                    if (cellW <= 0 || cellH <= 0) return@Thread
                    val interval = total / count
                    val list = ArrayList<Bitmap>()
                    for (i in 0 until count) {
                        val f = mmr.getFrameAtTime(i * interval * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                        list.add(centerCrop(f, cellW, cellH))
                    }
                    mainHandler.post { frames = list; invalidate() }
                }
                mmr.release()
            } catch (e: Exception) {
                try { mmr?.release() } catch (_: Exception) {}
            }
        }
        loadThread?.start()
    }

    fun setWaveform(amplitudes: FloatArray) {
        waveform = amplitudes
        invalidate()
    }

    /* =============== measure =============== */
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val contentH = maxOf(stripHeight + frameStrokeH * 2, handleH, playheadH)
        val w = resolveSize(suggestedMinimumWidth, wSpec)
        val h = resolveSize((bubbleTopSpace() + contentH).toInt(), hSpec)
        setMeasuredDimension(w, h)
    }

    private fun bubbleTopSpace(): Float {
        if (!showBubble) return 0f
        val frameTopFromCenter = stripHeight / 2f + frameStrokeH
        val bubbleTopFromCenter = bubbleMarginY + dp(6f) + bubbleBoxHeight()
        return max(0f, bubbleTopFromCenter - frameTopFromCenter) + dp(2f)
    }

    private fun bubbleBoxHeight(): Float {
        val fm = bubbleTextPaint.fontMetrics
        return (fm.descent - fm.ascent) + dp(10f)
    }

    /* =============== draw =============== */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isInEditMode && durationMs == 0L) {
            durationMs = 100_000L; startMs = 0L; endMs = 50_000L; currentMs = 30_000L
        }
        val w = width.toFloat()
        if (w <= 0) return

        val contentH = maxOf(stripHeight + frameStrokeH * 2, handleH, playheadH)
        val centerY = bubbleTopSpace() + contentH / 2f

        outerL = 0f; outerR = w
        outerT = centerY - (stripHeight + frameStrokeH * 2) / 2f
        outerB = centerY + (stripHeight + frameStrokeH * 2) / 2f

        innerL = outerL + frameStrokeV
        innerR = outerR - frameStrokeV
        stripT = outerT + frameStrokeH
        stripB = outerB - frameStrokeH

        rect.set(innerL, stripT, innerR, stripB)
        innerPath.reset(); innerPath.addRoundRect(rect, thumbCorner, thumbCorner, Path.Direction.CW)
        innerPaint.color = innerColor
        canvas.drawPath(innerPath, innerPaint)

        canvas.save()
        clipPath.reset(); clipPath.addRoundRect(rect, thumbCorner, thumbCorner, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawThumbs(canvas)
        if (showDim) drawDim(canvas)
        canvas.restore()

        rect.set(outerL, outerT, outerR, outerB)
        outerPath.reset(); outerPath.addRoundRect(rect, frameCorner, frameCorner, Path.Direction.CW)
        framePath.reset()
        framePath.op(outerPath, innerPath, Path.Op.DIFFERENCE)
        framePaint.color = frameColor
        canvas.drawPath(framePath, framePaint)

        drawHandle(canvas, leftBmp, handleX(LEFT))
        drawHandle(canvas, rightBmp, handleX(RIGHT))

        if (showPlayhead) {
            val x = playheadX()
            val top = (stripT + stripB) / 2f - playheadH / 2f
            rect.set(x, top, x + playheadW, top + playheadH)
            if (playheadBmp != null) canvas.drawBitmap(playheadBmp!!, null, rect, null)
            else {
                handlePaint.color = playheadColor
                canvas.drawRoundRect(rect, playheadCorner, playheadCorner, handlePaint)
            }
        }

        if (showBubble) drawBubble(canvas, w)
    }

    private fun drawThumbs(canvas: Canvas) {
        val left = innerL + thumbML
        val right = innerR - thumbMR
        val top = stripT + thumbMT
        val bottom = stripB - thumbMB
        val h = bottom - top

        when {
            frames.isNotEmpty() -> {
                var x = left
                for (b in frames) {
                    val bw = b.width.toFloat() / b.height * h
                    rect.set(x, top, x + bw, bottom)
                    canvas.drawBitmap(b, null, rect, null)
                    x += bw + thumbSpacing
                    if (x > right) break
                }
            }
            waveform.isNotEmpty() -> {
                // FIX: asli waveform bars -- audio trimmer me ab yeh dikhega
                val midY = (top + bottom) / 2f
                val maxBarHeight = h / 2f
                val barSpacing = dp(1.5f)
                val barWidth = ((right - left - barSpacing * (waveform.size - 1)) / waveform.size).coerceAtLeast(1f)

                var x = left
                for (amp in waveform) {
                    val barH = (amp.coerceIn(0f, 1f) * maxBarHeight).coerceAtLeast(dp(1.5f))
                    canvas.drawRoundRect(
                        x, midY - barH, x + barWidth, midY + barH,
                        barWidth / 2f, barWidth / 2f, wavePaint
                    )
                    x += barWidth + barSpacing
                    if (x > right) break
                }
            }
            else -> {
                val count = 8
                val cellW = (right - left - thumbSpacing * (count - 1)) / count
                var x = left
                for (i in 0 until count) {
                    rect.set(x, top, x + cellW, bottom)
                    canvas.drawRoundRect(rect, thumbCorner, thumbCorner, placeholderPaint)
                    x += cellW + thumbSpacing
                }
            }
        }
    }

    private fun drawDim(canvas: Canvas) {
        dimPaint.color = dimColor
        val selL = (handleX(LEFT) + handleW).coerceAtMost(innerR)
        val selR = handleX(RIGHT).coerceAtLeast(innerL)

        if (selL > innerL) canvas.drawRect(innerL, stripT, selL, stripB, dimPaint)
        if (selR < innerR) canvas.drawRect(selR, stripT, innerR, stripB, dimPaint)
    }

    private fun drawHandle(canvas: Canvas, bmp: Bitmap?, x: Float) {
        val top = (stripT + stripB) / 2f - handleH / 2f
        rect.set(x, top, x + handleW, top + handleH)
        if (bmp != null) canvas.drawBitmap(bmp, null, rect, null)
        else {
            handlePaint.color = handleColor
            canvas.drawRoundRect(rect, handleCorner, handleCorner, handlePaint)
        }
    }

    private fun drawBubble(canvas: Canvas, viewW: Float) {
        val text = String.format(Locale.ENGLISH, "%.2fs", (endMs - startMs) / 1000f)
        val tw = bubbleTextPaint.measureText(text)
        val boxH = bubbleBoxHeight()
        val boxW = tw + dp(24f)
        val midX = (handleX(LEFT) + handleX(RIGHT) + handleW) / 2f
        val boxL = (midX - boxW / 2).coerceIn(dp(2f), viewW - boxW - dp(2f))

        val anchorY = (stripT + stripB) / 2f
        val bubbleBottomY = anchorY - bubbleMarginY
        val boxBottom = bubbleBottomY - dp(6f)
        val boxTop = boxBottom - boxH

        bubbleFill.color = bubbleColor
        rect.set(boxL, boxTop, boxL + boxW, boxBottom)
        canvas.drawRoundRect(rect, bubbleCorner, bubbleCorner, bubbleFill)

        if (bubbleStrokeW > 0) {
            bubbleStroke.strokeWidth = bubbleStrokeW
            bubbleStroke.color = bubbleStrokeColor
            canvas.drawRoundRect(rect, bubbleCorner, bubbleCorner, bubbleStroke)
        }

        val ax = midX.coerceIn(boxL + bubbleCorner, boxL + boxW - bubbleCorner)
        arrowPath.reset()
        arrowPath.moveTo(ax - dp(5f), boxBottom)
        arrowPath.lineTo(ax + dp(5f), boxBottom)
        arrowPath.lineTo(ax, bubbleBottomY)
        arrowPath.close()
        canvas.drawPath(arrowPath, bubbleFill)

        val fm = bubbleTextPaint.fontMetrics
        bubbleTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, boxL + boxW / 2, boxTop + boxH / 2 - (fm.ascent + fm.descent) / 2, bubbleTextPaint)
        bubbleTextPaint.textAlign = Paint.Align.LEFT
    }

    /* =============== positions =============== */
    private fun usableSpan() = (outerR - outerL - handleW - handleMarginStart - handleMarginEnd).coerceAtLeast(1f)

    private fun handleX(which: Int): Float {
        val frac = when {
            durationMs <= 0 -> if (which == LEFT) 0f else 1f
            which == LEFT -> startMs.toFloat() / durationMs
            else -> endMs.toFloat() / durationMs
        }
        return outerL + handleMarginStart + frac * usableSpan()
    }

    private fun playheadX(): Float {
        val frac = if (durationMs <= 0) 0f else currentMs.toFloat() / durationMs
        return outerL + handleMarginStart + frac * usableSpan()
    }

    private fun xToMs(x: Float): Long {
        val frac = ((x - outerL - handleMarginStart) / usableSpan()).coerceIn(0f, 1f)
        return (frac * durationMs).toLong()
    }

    /* =============== touch =============== */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (durationMs <= 0 && !isInEditMode) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTarget = pickTarget(ev.x)
                if (dragTarget == NONE) return false
                touchOffset = when (dragTarget) {
                    LEFT -> ev.x - handleX(LEFT)
                    RIGHT -> ev.x - handleX(RIGHT)
                    else -> ev.x - playheadX()
                }
                if (dragTarget == LEFT || dragTarget == RIGHT) listener?.onTrimDragStart()
                parent?.requestDisallowInterceptTouchEvent(true)
                move(ev.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragTarget != NONE) move(ev.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTarget == LEFT || dragTarget == RIGHT) listener?.onTrimDragStop()
                dragTarget = NONE
                touchOffset = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun pickTarget(x: Float): Int {
        val slop = dp(12f)
        val lX = handleX(LEFT)
        val rX = handleX(RIGHT)
        val inLeft = x >= lX - slop && x <= lX + handleW + slop
        val inRight = x >= rX - slop && x <= rX + handleW + slop
        return when {
            inLeft && inRight -> if (abs(x - (lX + handleW / 2)) <= abs(x - (rX + handleW / 2))) LEFT else RIGHT
            inLeft -> LEFT
            inRight -> RIGHT
            showPlayhead && x >= playheadX() - slop && x <= playheadX() + playheadW + slop -> PLAYHEAD
            x > innerL && x < innerR -> PLAYHEAD
            else -> NONE
        }
    }

    private fun move(x: Float) {
        val ms = xToMs(x - touchOffset)
        when (dragTarget) {
            LEFT -> {
                startMs = ms.coerceIn(0L, (endMs - minTrimMs).coerceAtLeast(0L))
                if (currentMs < startMs) currentMs = startMs
                listener?.onTrimChanged(startMs, endMs)
            }
            RIGHT -> {
                endMs = ms.coerceIn((startMs + minTrimMs).coerceAtMost(durationMs), durationMs)
                if (currentMs > endMs) currentMs = endMs
                listener?.onTrimChanged(startMs, endMs)
            }
            PLAYHEAD -> {
                currentMs = ms.coerceIn(startMs, endMs)
                listener?.onPlayheadMoved(currentMs)
            }
        }
        invalidate()
    }

    /* =============== utils =============== */
    private fun drawableToBitmap(d: Drawable?, w: Int, h: Int): Bitmap? {
        if (d == null) return null
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return bmp
    }

    private fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val scale = max(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt()
        val scaledH = (src.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = max(0, (scaledW - targetW) / 2)
        val y = max(0, (scaledH - targetH) / 2)
        return Bitmap.createBitmap(scaled, x, y, targetW, targetH)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}