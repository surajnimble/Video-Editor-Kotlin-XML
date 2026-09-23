package com.app.videoeditor.gl

import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import androidx.core.util.Consumer
import java.util.concurrent.Executor

/**
 * CameraEffect's own constructor is protected - it's designed to be subclassed, not
 * instantiated directly. This just exposes it with the LUT-specific defaults MainActivity needs.
 */
class LutCameraEffect(
    surfaceProcessor: SurfaceProcessor,
    executor: Executor,
    errorListener: Consumer<Throwable>
) : CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, surfaceProcessor, errorListener)