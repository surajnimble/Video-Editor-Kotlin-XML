package com.app.videoeditor.trimmer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.sqrt

/**
 * Audio file se halka waveform (per-bucket RMS amplitude, 0..1 normalized)
 * nikalta hai -- TrimTimelineView me isse asli waveform draw hota hai
 * (placeholder blocks ki jagah). Poora audio decode karta hai, isliye
 * lambi files par thoda time lag sakta hai -- background thread par chalao.
 */
object WaveformExtractor {

    fun extract(context: Context, uri: Uri, barCount: Int = 150): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIndex = i; format = f; break }
            }
            if (audioTrackIndex == -1 || format == null) return FloatArray(0)
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val peaks = ArrayList<Float>()
            var sumSquares = 0.0
            var sampleCountInBucket = 0

            val totalDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val bucketDurationUs = if (totalDurationUs > 0) totalDurationUs / barCount else 100_000L

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        val pcm = ShortArray(bufferInfo.size / 2)
                        outBuffer.asShortBuffer().get(pcm)
                        for (s in pcm) {
                            sumSquares += (s.toDouble() * s.toDouble())
                            sampleCountInBucket++
                        }
                        if (bufferInfo.presentationTimeUs >= (peaks.size + 1) * bucketDurationUs && sampleCountInBucket > 0) {
                            peaks.add(sqrt(sumSquares / sampleCountInBucket).toFloat())
                            sumSquares = 0.0
                            sampleCountInBucket = 0
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            if (sampleCountInBucket > 0) {
                peaks.add(sqrt(sumSquares / sampleCountInBucket).toFloat())
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (peaks.isEmpty()) return FloatArray(0)
            val max = peaks.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            return FloatArray(peaks.size) { (peaks[it] / max).coerceIn(0f, 1f) }
        } catch (e: Exception) {
            try { extractor.release() } catch (_: Exception) {}
            return FloatArray(0)
        }
    }
}