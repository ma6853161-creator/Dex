package com.opendex.receiver.receiver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Thin wrapper around MediaCodec configured for lowest-latency decode-to-surface:
 *  - Zero-copy: decoder writes straight to the Surface (no ByteBuffer round trip
 *    on the output side), so frames go decoder -> SurfaceFlinger directly.
 *  - KEY_LOW_LATENCY hint (API 30+) asks the vendor codec to disable internal
 *    frame reordering/buffering where supported.
 *  - Asynchronous callback API avoids the dequeue/poll loop overhead.
 */
class VideoDecoder(
    private val outputSurface: Surface,
    private val onError: (Throwable) -> Unit
) {
    private var codec: MediaCodec? = null
    private var configured = false

    fun start(width: Int, height: Int, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
        stop()

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Frames are fed synchronously from feed(); nothing queued here
                // proactively — see note in feed().
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                // true = render immediately to the Surface, zero-copy path
                codec.releaseOutputBuffer(index, true)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // resolution/DPI change from sender — Phase 1 logs only,
                // Phase 2 will tear down & reconfigure the Surface here.
            }
        })

        decoder.configure(format, outputSurface, null, 0)
        decoder.start()
        codec = decoder
        configured = true
    }

    /**
     * Feeds one Annex-B NAL unit into the decoder. Uses the synchronous
     * dequeueInputBuffer with a short timeout rather than queuing from the
     * async callback, which keeps backpressure predictable under bursty
     * network delivery — important once we add adaptive bitrate in a later
     * phase.
     */
    fun feed(nalUnit: ByteArray, presentationTimeUs: Long) {
        val decoder = codec ?: return
        if (!configured) return

        val inputIndex = decoder.dequeueInputBuffer(10_000) // 10ms timeout
        if (inputIndex < 0) return // drop frame under backpressure, favor latency over completeness

        val inputBuffer: ByteBuffer = decoder.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(nalUnit)
        decoder.queueInputBuffer(inputIndex, 0, nalUnit.size, presentationTimeUs, 0)
    }

    fun stop() {
        configured = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
