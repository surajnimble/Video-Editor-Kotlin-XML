package com.app.videoeditor.widget

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draggable / pinch-zoom / rotate sticker overlay. Emoji (text) OR ek image/GIF
 * dono support karta hai -- jo bhi set ho wo draw hota hai (imageUri priority
 * leta hai). GIF automatically animate hoti hai (ImageDecoder ka
 * AnimatedImageDrawable, API 28+ ka built-in feature -- koi extra library
 * nahi chahiye).
 */
class StickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var emoji: String = ""
        set(value) { field = value; invalidate() }

    // Set karte hi background thread par decode hoti hai; imageUri = null karne
    // par wapas emoji mode par chali jaati hai.
    var imageUri: Uri? = null
        set(value) {
            field = value
            stickerDrawable = null
            if (value != null) loadDrawable(value)
            invalidate()
        }

    var fractionCenterX: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }
    var fractionCenterY: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }

    var fractionSize: Float = 0.18f
        set(value) { field = value.coerceIn(0.01f, 20f); invalidate() }

    var rotationDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    var isStickerSelected: Boolean = false
        set(value) { field = value; invalidate() }

    var onActionEnded: ((StickerView) -> Unit)? = null
    var onTapped: ((StickerView) -> Unit)? = null

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4A90E2.toInt()
    }

    private var stickerDrawable: Drawable? = null
    private var drawableAspect = 1f // width/height, decode hone tak default square

    // Local (unrotated, untranslated) bounds, centered on (0,0).
    private var localBounds = RectF()
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private var isDragging = false
    private var isPinching = false
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var startFractionX = 0f
    private var startFractionY = 0f
    private var startPinchDistance = 0f
    private var startPinchAngle = 0f
    private var startFractionSize = 0f
    private var startRotation = 0f

    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId = MotionEvent.INVALID_POINTER_ID

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    private fun loadDrawable(uri: Uri) {
        val requestedUri = uri
        Thread {
            val drawable = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeDrawable(source)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapDrawable(resources, BitmapFactory.decodeStream(it))
                    }
                }
            } catch (e: Exception) { null }

            post {
                // Race guard: is dauraan imageUri kahin aur na badal gayi ho
                if (imageUri != requestedUri || drawable == null) return@post
                drawable.callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) { invalidate() }
                    override fun scheduleDrawable(who: Drawable, what: Runnable, atTime: Long) {
                        Handler(Looper.getMainLooper()).postAtTime(what, atTime)
                    }
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                        Handler(Looper.getMainLooper()).removeCallbacks(what)
                    }
                }
                if (drawable is Animatable) (drawable as Animatable).start()
                drawableAspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
                stickerDrawable = drawable
                requestLayout()
                invalidate()
            }
        }.start()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return

        val drawSize = minOf(width, height) * fractionSize
        val drawable = stickerDrawable

        if (drawable != null) {
            // Aspect-fit box jitna drawSize
            val w: Float; val h: Float
            if (drawableAspect >= 1f) { w = drawSize; h = drawSize / drawableAspect }
            else { h = drawSize; w = drawSize * drawableAspect }
            localBounds = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        } else {
            emojiPaint.textSize = drawSize
            val glyphWidth = emojiPaint.measureText(emoji)
            val fm = emojiPaint.fontMetrics
            val baselineOffset = -(fm.ascent + fm.descent) / 2f
            localBounds = RectF(-glyphWidth / 2f, baselineOffset + fm.ascent, glyphWidth / 2f, baselineOffset + fm.descent)
        }

        drawMatrix.reset()
        drawMatrix.postRotate(rotationDegrees)
        drawMatrix.postTranslate(fractionCenterX * width, fractionCenterY * height)
        drawMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        rebuildGeometry()

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(drawMatrix)

        val drawable = stickerDrawable
        if (drawable != null) {
            drawable.setBounds(
                localBounds.left.toInt(), localBounds.top.toInt(),
                localBounds.right.toInt(), localBounds.bottom.toInt()
            )
            drawable.draw(canvas)
        } else {
            val fm = emojiPaint.fontMetrics
            val baseline = -(fm.ascent + fm.descent) / 2f
            canvas.drawText(emoji, 0f, baseline, emojiPaint)
        }

        if (isStickerSelected) {
            selectionPaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(localBounds, dp(4f), dp(4f), selectionPaint)
        }

        canvas.restore()
    }

    private fun hitTest(x: Float, y: Float): Boolean {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return localBounds.contains(pts[0], pts[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitTest(event.x, event.y)) return false

                isStickerSelected = true
                onTapped?.invoke(this)
                isDragging = true
                isPinching = false
                primaryPointerId = event.getPointerId(0)
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID

                startTouchX = event.x
                startTouchY = event.y
                startFractionX = fractionCenterX
                startFractionY = fractionCenterY
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isDragging || isPinching) return true

                val newIndex = event.actionIndex
                val newX = event.getX(newIndex)
                val newY = event.getY(newIndex)

                val primaryIndex = event.findPointerIndex(primaryPointerId)
                if (primaryIndex == -1) return true

                secondaryPointerId = event.getPointerId(newIndex)
                isPinching = true

                val px = event.getX(primaryIndex)
                val py = event.getY(primaryIndex)
                startPinchDistance = hypot(px - newX, py - newY)
                startPinchAngle = atan2(py - newY, px - newX)
                startFractionSize = fractionSize
                startRotation = rotationDegrees

                startTouchX = (px + newX) / 2f
                startTouchY = (py + newY) / 2f
                startFractionX = fractionCenterX
                startFractionY = fractionCenterY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val primaryIndex = event.findPointerIndex(primaryPointerId)
                if (primaryIndex == -1) return true

                if (isPinching) {
                    val secondaryIndex = event.findPointerIndex(secondaryPointerId)
                    if (secondaryIndex == -1) {
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        startTouchX = event.getX(primaryIndex)
                        startTouchY = event.getY(primaryIndex)
                        startFractionX = fractionCenterX
                        startFractionY = fractionCenterY
                    } else {
                        val px = event.getX(primaryIndex); val py = event.getY(primaryIndex)
                        val sx = event.getX(secondaryIndex); val sy = event.getY(secondaryIndex)

                        val curMidX = (px + sx) / 2f
                        val curMidY = (py + sy) / 2f
                        fractionCenterX = startFractionX + (curMidX - startTouchX) / width
                        fractionCenterY = startFractionY + (curMidY - startTouchY) / height

                        val dist = hypot(px - sx, py - sy)
                        fractionSize = startFractionSize * (dist / startPinchDistance)

                        val angleDiff = atan2(py - sy, px - sx) - startPinchAngle
                        rotationDegrees = startRotation + Math.toDegrees(angleDiff.toDouble()).toFloat()

                        invalidate()
                        return true
                    }
                }

                val curX = event.getX(primaryIndex)
                val curY = event.getY(primaryIndex)
                fractionCenterX = startFractionX + (curX - startTouchX) / width
                fractionCenterY = startFractionY + (curY - startTouchY) / height
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                when {
                    isPinching && liftedId == secondaryPointerId -> {
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        val primaryIndex = event.findPointerIndex(primaryPointerId)
                        if (primaryIndex != -1) {
                            startTouchX = event.getX(primaryIndex)
                            startTouchY = event.getY(primaryIndex)
                            startFractionX = fractionCenterX
                            startFractionY = fractionCenterY
                        }
                    }
                    liftedId == primaryPointerId && secondaryPointerId != MotionEvent.INVALID_POINTER_ID -> {
                        val promotedIndex = event.findPointerIndex(secondaryPointerId)
                        primaryPointerId = secondaryPointerId
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        isPinching = false
                        if (promotedIndex != -1) {
                            startTouchX = event.getX(promotedIndex)
                            startTouchY = event.getY(promotedIndex)
                            startFractionX = fractionCenterX
                            startFractionY = fractionCenterY
                        }
                    }
                    liftedId == primaryPointerId -> {
                        isDragging = false
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        performClick()
                        onActionEnded?.invoke(this)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging || isPinching) {
                    isDragging = false
                    isPinching = false
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                    performClick()
                    onActionEnded?.invoke(this)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun dp(v: Float): Float = (v * resources.displayMetrics.density).coerceAtLeast(0f)
}