package com.app.videoeditor.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.app.videoeditor.lut.LutData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Applies the active LUT to every camera frame in real time and forwards the result to
 * every attached output (Preview + VideoCapture both get the exact same filtered frames,
 * since both are fed by this single processor).
 *
 * All GL work happens on one dedicated thread - GL contexts are not thread-safe, so
 * nothing here may touch GL objects from any other thread.
 */
class LutSurfaceProcessor : SurfaceProcessor {

    private val glThread = HandlerThread("LutGLThread").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command -> glHandler.post(command) }

    private val egl = EglCore()
    private var offscreenSurface: EGLSurface? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uOutputMatrixLoc = 0
    private var uCameraTexLoc = 0
    private var uLut3DTexLoc = 0
    private var uLut1DTexLoc = 0
    private var uLutModeLoc = 0
    private var uLutStrengthLoc = 0

    private var cameraTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    private data class OutputTarget(val surfaceOutput: SurfaceOutput, val eglSurface: EGLSurface, val width: Int, val height: Int)
    private val outputs = ConcurrentHashMap<SurfaceOutput, OutputTarget>()

    @Volatile private var lutTextureId = 0
    @Volatile private var lutMode = 0 // 0 = none, 1 = 3D, 2 = 1D
    @Volatile private var lutSize = 0

    private var neutralLut3DTex = 0
    private var neutralLut1DTex = 0

    /** 0 = original camera image, 1 = full filter strength - mirrors IG's own filter-intensity slider. */
    @Volatile var lutStrength: Float = 1f

    init {
        glHandler.post {
            try {
                egl.init()
                offscreenSurface = egl.createOffscreenSurface()
                egl.makeCurrent(offscreenSurface!!)
                setupGl()
                android.util.Log.i(TAG, "GL initialized, context ready")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "GL init failed - effect is broken", e)
                throw e
            }
        }
    }

    private fun setupGl() {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        cameraTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(cameraTexId).apply {
            setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
        }
        inputSurface = Surface(surfaceTexture)

        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uOutputMatrixLoc = GLES20.glGetUniformLocation(program, "uOutputMatrix")
        uCameraTexLoc = GLES20.glGetUniformLocation(program, "uCameraTex")
        uLut3DTexLoc = GLES20.glGetUniformLocation(program, "uLut3DTex")
        uLut1DTexLoc = GLES20.glGetUniformLocation(program, "uLut1DTex")
        uLutModeLoc = GLES20.glGetUniformLocation(program, "uLutMode")
        uLutStrengthLoc = GLES20.glGetUniformLocation(program, "uLutStrength")

        neutralLut3DTex = uploadNeutralLut3D()
        neutralLut1DTex = uploadNeutralLut1D()
    }

    private fun uploadNeutralLut3D(): Int {
        val size = 2
        val data = FloatArray(size * size * size * 3)
        for (i in 0 until size) for (j in 0 until size) for (k in 0 until size) {
            val idx = (i * size * size + j * size + k) * 3
            data[idx]     = i / (size - 1f)
            data[idx + 1] = j / (size - 1f)
            data[idx + 2] = k / (size - 1f)
        }
        return ShaderUtil.uploadLut3DTexture(size, data)
    }

    private fun uploadNeutralLut1D(): Int {
        val size = 2
        val red = FloatArray(size) { it / (size - 1f) }
        return ShaderUtil.upload1DAsRow(size, red, red, red)
    }

    // ===================== Public control =====================

    /** Swap the active filter live. Pass null to go back to the unfiltered "Normal" look. */
    fun setLut(lut: LutData?) {
        glHandler.post {
            if (lut == null) {
                lutMode = 0
                return@post
            }
            when (lut) {
                is LutData.Lut3D -> {
                    lutTextureId = ShaderUtil.uploadLut3DTexture(lut.size, lut.data)
                    lutSize = lut.size
                    lutMode = 1
                }
                is LutData.Lut1D -> {
                    lutTextureId = ShaderUtil.upload1DAsRow(lut.size, lut.red, lut.green, lut.blue)
                    lutSize = lut.size
                    lutMode = 2
                }
            }
        }
    }

    // ===================== SurfaceProcessor contract =====================

    override fun onInputSurface(request: SurfaceRequest) {
        android.util.Log.i(TAG, "onInputSurface called, resolution=${request.resolution}")
        glHandler.post {
            try {
                val surface = inputSurface ?: return@post
                surfaceTexture?.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                request.provideSurface(surface, glExecutor) { result ->
                    android.util.Log.w(TAG, "provideSurface result: ${resultCodeLabel(result.resultCode)}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "onInputSurface failed", e)
                request.invalidate()
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        android.util.Log.i(TAG, "onOutputSurface called, size=${surfaceOutput.size}, targets=${surfaceOutput.targets}")
        glHandler.post {
            try {
                val surface = surfaceOutput.getSurface(glExecutor) { result ->
                    glHandler.post {
                        outputs.remove(surfaceOutput)?.let { egl.destroySurface(it.eglSurface) }
                        surfaceOutput.close()
                    }
                    android.util.Log.w(TAG, "output surface released: $result")
                }
                val size = surfaceOutput.size
                val eglSurface = egl.createWindowSurface(surface)
                outputs[surfaceOutput] = OutputTarget(surfaceOutput, eglSurface, size.width, size.height)
                checkGlError("createWindowSurface")
                android.util.Log.i(TAG, "output registered, total outputs=${outputs.size}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "onOutputSurface failed", e)
                surfaceOutput.close()
            }
        }
    }
    private fun onFrameAvailable() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            if (outputs.isEmpty()) {
                android.util.Log.w(TAG, "frame available but no output surfaces yet")
                return
            }
            if (program == 0) {
                android.util.Log.e(TAG, "frame available but GL program is 0 - nothing will be drawn")
                return
            }

            for (target in outputs.values) {
                egl.makeCurrent(target.eglSurface)
                checkGlError("makeCurrent")
                GLES20.glViewport(0, 0, target.width, target.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)

                val textureTransform = FloatArray(16)
                Matrix.setIdentityM(textureTransform, 0)
                target.surfaceOutput.updateTransformMatrix(textureTransform, texMatrix)
                checkGlError("updateTransformMatrix")

                val positionMatrix = FloatArray(16)
                Matrix.setIdentityM(positionMatrix, 0)

                drawQuad(textureTransform, positionMatrix)
                checkGlError("drawQuad")

                egl.setPresentationTime(target.eglSurface, st.timestamp)
                egl.swapBuffers(target.eglSurface)
            }
            if (android.util.Log.isLoggable(TAG, android.util.Log.VERBOSE)) {
                android.util.Log.v(TAG, "frame drawn to ${outputs.size} outputs")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "frame processing failed - preview will stay black", e)
        }
    }

    private fun checkGlError(where: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            android.util.Log.e(TAG, "GL error at $where: 0x${Integer.toHexString(err)}")
        }
    }

    private fun resultCodeLabel(code: Int): String = when (code) {
        SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY -> "SURFACE_USED_SUCCESSFULLY"
        SurfaceRequest.Result.RESULT_INVALID_SURFACE -> "INVALID_SURFACE"
        SurfaceRequest.Result.RESULT_REQUEST_CANCELLED -> "REQUEST_CANCELLED"
        SurfaceRequest.Result.RESULT_SURFACE_ALREADY_PROVIDED -> "SURFACE_ALREADY_PROVIDED"
        else -> "code=$code"
    }

    private fun drawQuad(textureTransform: FloatArray, positionMatrix: FloatArray) {
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, textureTransform, 0)
        GLES20.glUniformMatrix4fv(uOutputMatrixLoc, 1, false, positionMatrix, 0)

        val lut3DTex = if (lutMode == 1) lutTextureId else neutralLut3DTex
        val lut1DTex = if (lutMode == 2) lutTextureId else neutralLut1DTex

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(uCameraTexLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3DTex)
        GLES20.glUniform1i(uLut3DTexLoc, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lut1DTex)
        GLES20.glUniform1i(uLut1DTexLoc, 2)

        GLES20.glUniform1i(uLutModeLoc, lutMode)
        GLES20.glUniform1f(uLutStrengthLoc, lutStrength)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUF)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, TEXCOORD_BUF)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        glHandler.post {
            outputs.values.forEach { egl.destroySurface(it.eglSurface) }
            outputs.clear()
            surfaceTexture?.release()
            inputSurface?.release()
            egl.release()
        }
        glThread.quitSafely()
    }

    companion object {
        private const val TAG = "LutGL"
        private val VERTEX_BUF: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEXCOORD_BUF: FloatBuffer = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuf(arr: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(arr); position(0) }

        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uOutputMatrix;
            uniform mat4 uTexMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uOutputMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            precision mediump sampler2D;
            precision mediump sampler3D;
            precision mediump samplerExternalOES;
            in vec2 vTexCoord;
            uniform samplerExternalOES uCameraTex;
            uniform sampler3D uLut3DTex;
            uniform sampler2D uLut1DTex;
            uniform int uLutMode;
            uniform float uLutStrength;
            out vec4 fragColor;

            void main() {
                vec4 base = texture(uCameraTex, vTexCoord);
                vec3 color = base.rgb;

                if (uLutMode == 1) {
                    vec3 graded = texture(uLut3DTex, color).rgb;
                    color = mix(color, graded, uLutStrength);
                } else if (uLutMode == 2) {
                    float r = texture(uLut1DTex, vec2(color.r, 0.5)).r;
                    float g = texture(uLut1DTex, vec2(color.g, 0.5)).g;
                    float b = texture(uLut1DTex, vec2(color.b, 0.5)).b;
                    color = mix(color, vec3(r, g, b), uLutStrength);
                }

                fragColor = vec4(color, base.a);
            }
        """
    }
}