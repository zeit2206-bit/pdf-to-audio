package com.zeit2206.audiobook

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

// Silent-ending detection (e.g. the TikTok outro: logo card with no sound).
// Only the last SCAN_MS are decoded; a tail is trimmed when it's at least MIN_TAIL_MS of silence.
private const val SCAN_MS = 15_000L
private const val WINDOW_MS = 50
private const val SILENCE_DB = -50.0
private const val MIN_TAIL_MS = 1_000L
// The outro image starts the instant the sound stops, so cut one window early rather than late.

/**
 * Returns where playback should end (ms) if the video ends in silence, or 0 to play it whole.
 * Videos with no audio, or silent for the whole scanned stretch, are left alone.
 */
fun detectSilentTail(context: Context, uri: Uri): Long {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return 0
        val format = ex.getTrackFormat(track)
        val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else return 0
        ex.selectTrack(track)
        val startMs = (durationMs - SCAN_MS).coerceAtLeast(0)
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var lastLoudEndMs = -1L
        var firstPcmMs = -1L
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer() // 16-bit PCM
                    val frames = buf.remaining() / channels
                    val win = rate * WINDOW_MS / 1000
                    val t0 = info.presentationTimeUs / 1000
                    if (firstPcmMs < 0 && frames > 0) firstPcmMs = t0
                    var f = 0
                    while (f < frames) {
                        val end = minOf(f + win, frames)
                        var sum = 0.0
                        for (k in f * channels until end * channels) { val s = buf.get(k) / 32768.0; sum += s * s }
                        val rms = sqrt(sum / ((end - f) * channels))
                        if (rms > 0 && 20 * log10(rms) > SILENCE_DB) lastLoudEndMs = t0 + end * 1000L / rate
                        f = end
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop(); codec.release()
        }
        if (lastLoudEndMs < 0 || lastLoudEndMs <= firstPcmMs) return 0 // all silent: nothing to go by
        val cut = lastLoudEndMs - WINDOW_MS
        return if (durationMs - cut >= MIN_TAIL_MS) cut else 0
    } catch (e: Exception) {
        return 0
    } finally {
        ex.release()
    }
}
