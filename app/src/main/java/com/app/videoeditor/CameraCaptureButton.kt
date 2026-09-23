package com.app.videoeditor.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.app.videoeditor.R
import kotlin.math.min

/**
 * Instagram / Snapchat style camera capture button.
 *
 * Every color, ratio, timing and feature is overridable from XML (see
 * res/values/attrs_camera_capture_button.xml) or from code via the public
 * vars / setters below - nothing is hardcoded.
 *
 * - Quick tap   -> onCapturePhoto()                     (if ccb_photoEnabled)
 * - Press+hold  -> onRecordStart() -> onRecordProgress(fraction) repeatedly
 *                  -> onRecordStop(durationMs) on release/timeout            (if ccb_videoEnabled)
 * - Drag finger up/down while holding -> onZoomChange(zoomFactor)            (if ccb_zoomEnabled)
 *
 * Design-time preview: set app:ccb_previewState="recording" in XML to see the
 * recording visuals directly in Android Studio's Layout Editor without running
 * the app. It has no effect at runtime (guarded by isInEditMode).
 *
 * Zoom range is NOT a fixed guess: call [setZoomRange] once you know the real
 * camera's min/max zoom ratio (e.g. from CameraX's ZoomState, or Camera2's
 * CameraCharacteristics) - see MainActivity for a worked example.
 */
class CameraCaptureButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCapturePhoto() {}
        fun onRecordStart() {}
        fun onRecordProgress(fraction: Float) {}
        fun onRecordStop(durationMs: Long) {}
        /** zoomFactor ranges between [minZoomFactor] and [maxZoomFactor]; fires only while recording. */
        fun onZoomChange(zoomFactor: Float) {}
    }

    var listener: Listener? = null

    // ===================== Customizable via XML (ccb_* attrs) or code =====================

    var ringColorIdle: Int = Color.WHITE
    var ringColorRecording: Int = Color.parseColor("#FF3B30")
    var innerColorIdle: Int = Color.WHITE
    var innerColorRecording: Int = Color.parseColor("#FF3B30")

    var ringStrokeWidthRatio: Float = 0.09f
    var innerRadiusRatioIdle: Float = 0.80f
    var innerRadiusRatioRecording: Float = 0.55f
    var ringGrowthRatio: Float = 0.06f
    var pressScaleTarget: Float = 0.88f
    var idleRingAlpha: Int = 200
    var recordingTrackAlpha: Int = 60

    var maxHoldMs: Long = 15_000L
    var longPressThresholdMs: Long = 200L

    var zoomEnabled: Boolean = true
    var zoomDragRangePx: Float = 0f   // resolved from dp default in init if not set via XML
    var minZoomFactor: Float = 1.0f
    var maxZoomFactor: Float = 4.0f
        private set

    var photoEnabled: Boolean = true
    var videoEnabled: Boolean = true
    var hapticsEnabled: Boolean = true
    var pressAnimEnabled: Boolean = true
    var recordPulseEnabled: Boolean = true

    private var previewStateRecording: Boolean = false
    private var previewProgress: Float = 0.35f

    /**
     * Call this once you know the real camera's zoom range (CameraX ZoomState.minZoomRatio /
     * maxZoomRatio, or Camera2's SCALER_AVAILABLE_MAX_DIGITAL_ZOOM). Never assume a fixed number -
     * it varies a lot between devices.
     */
    fun setZoomRange(min: Float, max: Float) {
        minZoomFactor = min.coerceAtLeast(1f)
        maxZoomFactor = max.coerceAtLeast(minZoomFactor)
        currentZoom = minZoomFactor
    }

    // ===================== Internals =====================

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringRect = RectF()

    private var innerScale = 1f
    private var ringExpand = 0f
    private var progressFraction = 0f
    private var isRecording = false

    private var pressScaleAnimator: ValueAnimator? = null
    private var recordAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var recordStartTime = 0L

    private var downY = 0f
    private var currentZoom = 1f

    init {
        attrs?.let { readAttrs(it, defStyleAttr) }
        if (zoomDragRangePx <= 0f) {
            zoomDragRangePx = 220f * resources.displayMetrics.density
        }
        currentZoom = minZoomFactor
        applyPreviewStateIfEditing()
    }

    private fun readAttrs(attrs: AttributeSet, defStyleAttr: Int) {
        val ta: TypedArray = context.obtainStyledAttributes(
            attrs, R.styleable.CameraCaptureButton, defStyleAttr, 0
        )
        try {
            ringColorIdle = ta.getColor(R.styleable.CameraCaptureButton_ccb_ringColorIdle, ringColorIdle)
            ringColorRecording = ta.getColor(R.styleable.CameraCaptureButton_ccb_ringColorRecording, ringColorRecording)
            innerColorIdle = ta.getColor(R.styleable.CameraCaptureButton_ccb_innerColorIdle, innerColorIdle)
            innerColorRecording = ta.getColor(R.styleable.CameraCaptureButton_ccb_innerColorRecording, innerColorRecording)

            ringStrokeWidthRatio = ta.getFloat(R.styleable.CameraCaptureButton_ccb_ringStrokeWidthRatio, ringStrokeWidthRatio)
            innerRadiusRatioIdle = ta.getFloat(R.styleable.CameraCaptureButton_ccb_innerRadiusRatioIdle, innerRadiusRatioIdle)
            innerRadiusRatioRecording = ta.getFloat(R.styleable.CameraCaptureButton_ccb_innerRadiusRatioRecording, innerRadiusRatioRecording)
            ringGrowthRatio = ta.getFloat(R.styleable.CameraCaptureButton_ccb_ringGrowthRatio, ringGrowthRatio)
            pressScaleTarget = ta.getFloat(R.styleable.CameraCaptureButton_ccb_pressScale, pressScaleTarget)
            idleRingAlpha = ta.getInt(R.styleable.CameraCaptureButton_ccb_idleRingAlpha, idleRingAlpha)
            recordingTrackAlpha = ta.getInt(R.styleable.CameraCaptureButton_ccb_recordingTrackAlpha, recordingTrackAlpha)

            maxHoldMs = ta.getInt(R.styleable.CameraCaptureButton_ccb_maxHoldMs, maxHoldMs.toInt()).toLong()
            longPressThresholdMs = ta.getInt(R.styleable.CameraCaptureButton_ccb_longPressThresholdMs, longPressThresholdMs.toInt()).toLong()

            zoomEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_zoomEnabled, zoomEnabled)
            zoomDragRangePx = ta.getDimension(R.styleable.CameraCaptureButton_ccb_zoomDragRange, 0f)
            minZoomFactor = ta.getFloat(R.styleable.CameraCaptureButton_ccb_minZoomFactor, minZoomFactor)
            maxZoomFactor = ta.getFloat(R.styleable.CameraCaptureButton_ccb_maxZoomFactor, maxZoomFactor)

            photoEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_photoEnabled, photoEnabled)
            videoEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_videoEnabled, videoEnabled)
            hapticsEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_hapticsEnabled, hapticsEnabled)
            pressAnimEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_pressAnimEnabled, pressAnimEnabled)
            recordPulseEnabled = ta.getBoolean(R.styleable.CameraCaptureButton_ccb_recordPulseEnabled, recordPulseEnabled)

            previewStateRecording = ta.getInt(R.styleable.CameraCaptureButton_ccb_previewState, 0) == 1
            previewProgress = ta.getFloat(R.styleable.CameraCaptureButton_ccb_previewProgress, previewProgress)
        } finally {
            ta.recycle()
        }
    }

    /** Lets Android Studio's Layout Editor show the recording look without running the app. */
    private fun applyPreviewStateIfEditing() {
        if (!isInEditMode) return
        if (previewStateRecording) {
            isRecording = true
            progressFraction = previewProgress.coerceIn(0f, 1f)
            ringExpand = 0f
            innerScale = 1f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (min(width, height) / 2f) * 0.86f

        val strokeW = baseRadius * ringStrokeWidthRatio
        idlePaint.strokeWidth = strokeW
        progressPaint.strokeWidth = strokeW
        progressPaint.color = ringColorRecording

        val outerRadius = baseRadius + ringExpand
        ringRect.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)

        if (isRecording) {
            idlePaint.color = ringColorIdle
            idlePaint.alpha = recordingTrackAlpha
            canvas.drawOval(ringRect, idlePaint)
            canvas.drawArc(ringRect, -90f, 360f * progressFraction, false, progressPaint)
        } else {
            idlePaint.color = ringColorIdle
            idlePaint.alpha = idleRingAlpha
            canvas.drawOval(ringRect, idlePaint)
        }

        innerPaint.color = if (isRecording) innerColorRecording else innerColorIdle
        val innerRatio = if (isRecording) innerRadiusRatioRecording else innerRadiusRatioIdle
        val innerRadius = baseRadius * innerRatio * innerScale
        canvas.drawCircle(cx, cy, innerRadius, innerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                animatePress(down = true)
                if (videoEnabled) {
                    longPressRunnable = Runnable { beginRecording() }.also {
                        handler.postDelayed(it, longPressThresholdMs)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRecording && zoomEnabled) {
                    val dragUp = (downY - event.y).coerceAtLeast(0f)
                    val fraction = (dragUp / zoomDragRangePx).coerceIn(0f, 1f)
                    val newZoom = minZoomFactor + fraction * (maxZoomFactor - minZoomFactor)
                    if (newZoom != currentZoom) {
                        currentZoom = newZoom
                        listener?.onZoomChange(currentZoom)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                animatePress(down = false)
                if (isRecording) {
                    endRecording()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && isPointInside(event) && photoEnabled) {
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        listener?.onCapturePhoto()
        return true
    }

    private fun isPointInside(event: MotionEvent): Boolean =
        event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun animatePress(down: Boolean) {
        if (!pressAnimEnabled) {
            innerScale = 1f
            invalidate()
            return
        }
        pressScaleAnimator?.cancel()
        val target = if (down) pressScaleTarget else 1f
        pressScaleAnimator = ValueAnimator.ofFloat(innerScale, target).apply {
            duration = if (down) 100 else 180
            interpolator = OvershootInterpolator(if (down) 0f else 2.5f)
            addUpdateListener {
                innerScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun beginRecording() {
        isRecording = true
        recordStartTime = System.currentTimeMillis()
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onRecordStart()

        recordAnimator?.cancel()
        recordAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = maxHoldMs
            interpolator = null
            addUpdateListener { anim ->
                progressFraction = anim.animatedValue as Float
                if (recordPulseEnabled) {
                    ringExpand = (width.coerceAtLeast(1) / 2f) * ringGrowthRatio * progressFraction
                }
                listener?.onRecordProgress(progressFraction)
                invalidate()
                if (progressFraction >= 1f) endRecording()
            }
            start()
        }
    }

    private fun endRecording() {
        if (!isRecording) return
        isRecording = false
        recordAnimator?.cancel()
        val recordedDurationMs = System.currentTimeMillis() - recordStartTime

        val fromExpand = ringExpand
        ValueAnimator.ofFloat(fromExpand, 0f).apply {
            duration = 180
            interpolator = OvershootInterpolator(2f)
            addUpdateListener { anim ->
                ringExpand = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        progressFraction = 0f
        if (currentZoom != minZoomFactor) {
            currentZoom = minZoomFactor
            listener?.onZoomChange(minZoomFactor)
        }
        listener?.onRecordStop(recordedDurationMs)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingLongPress()
        pressScaleAnimator?.cancel()
        recordAnimator?.cancel()
    }
}