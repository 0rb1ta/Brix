package app.brix.streaming.overlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes a donation alert audio file to raw 16-bit PCM and exposes it for the
 * two routing options:
 *  - on-device: written to an [AudioTrack] so the streamer hears it;
 *  - in-stream: consumed chunk-by-chunk by [OverlayAudioSource] to mix with
 *    the microphone.
 *
 * PCM is decoded once into memory (alerts are short clips). Output is mono,
 * 48 kHz, 16-bit signed — matching the mic pipeline.
 */
class DonationAudioPlayer(
    private val sampleRate: Int = 48_000,
) {
    @Volatile
    private var pcm: ByteArray? = null
    @Volatile
    private var playingDevice = false
    private var audioTrack: AudioTrack? = null
    private val chunkLock = Any()
    private var chunkIndex = 0

    suspend fun load(file: File): Boolean = withContext(Dispatchers.Default) {
        try {
            pcm = decodeToPcm(file)
            android.util.Log.d("DonationAudio", "load: decoded ${pcm?.size ?: 0} bytes from ${file.name}")
            true
        } catch (e: Exception) {
            android.util.Log.e("DonationAudio", "load: decode failed for ${file.name}", e)
            false
        }
    }

    val loaded: Boolean get() = pcm != null

    val durationMs: Long get() = pcm?.let { it.size * 1000L / (sampleRate * 2) } ?: 0L

    /** Starts playback to the device speaker. */
    fun playOnDevice() {
        val data = pcm
        android.util.Log.d("DonationAudio", "playOnDevice: called, pcmBytes=${data?.size ?: 0}, alreadyPlaying=$playingDevice")
        if (data == null) return
        if (playingDevice) return
        playingDevice = true
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(data.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack = track
        try {
            val written = track.write(data, 0, data.size)
            track.play()
            android.util.Log.d("DonationAudio", "playOnDevice: wrote $written/${data.size} bytes, playState=${track.playState}")
        } catch (e: Exception) {
            android.util.Log.e("DonationAudio", "playOnDevice: AudioTrack write/play failed", e)
            track.release()
            audioTrack = null
        }
    }

    fun stopDevice() {
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
        audioTrack = null
        playingDevice = false
    }

    /** Resets the in-stream PCM cursor and starts yielding chunks. */
    fun beginStreamMix() {
        synchronized(chunkLock) { chunkIndex = 0 }
    }

    /** Returns the next [chunkBytes]-sized chunk of donation PCM, or null when exhausted. */
    fun nextStreamChunk(chunkBytes: Int): ByteArray? = pcm?.let { data ->
        synchronized(chunkLock) {
            val start = chunkIndex
            if (start >= data.size) return null
            val end = minOf(start + chunkBytes, data.size)
            chunkIndex = end
            data.copyOfRange(start, end)
        }
    }

    fun stop() {
        stopDevice()
        synchronized(chunkLock) { chunkIndex = 0 }
    }

    fun release() {
        stop()
        pcm = null
    }

    private fun decodeToPcm(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) throw IllegalStateException("No audio track")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var decodedRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = java.io.ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.get(bytes)
                            out.write(bytes)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        decodedRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }

            codec.stop()
            codec.release()

            val raw = out.toByteArray()
            val mono = if (channels > 1) downmixToMono(raw, channels) else raw
            return if (decodedRate != sampleRate) resample(mono, decodedRate, sampleRate) else mono
        } finally {
            extractor.release()
        }
    }

    /** Averages every [channels]-wide interleaved PCM16LE frame into one mono sample. */
    private fun downmixToMono(raw: ByteArray, channels: Int): ByteArray {
        val frameBytes = channels * 2
        val frameCount = raw.size / frameBytes
        val out = ByteArray(frameCount * 2)
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += readSample(raw, f * frameBytes + c * 2)
            }
            val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[f * 2] = (avg and 0xFF).toByte()
            out[f * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Linear-interpolation resample of mono PCM16LE from [fromRate] to [toRate]. */
    private fun resample(mono: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) return mono
        val srcSamples = mono.size / 2
        if (srcSamples == 0) return mono
        val dstSamples = (srcSamples.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = ByteArray(dstSamples * 2)
        val ratio = (srcSamples - 1).toFloat() / dstSamples.toFloat().coerceAtLeast(1f)
        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceIn(0, srcSamples - 1)
            val nextIdx = (idx + 1).coerceAtMost(srcSamples - 1)
            val frac = srcPos - idx
            val a = readSample(mono, idx * 2)
            val b = readSample(mono, nextIdx * 2)
            val interpolated = (a + (b - a) * frac).toInt()
            out[i * 2] = (interpolated and 0xFF).toByte()
            out[i * 2 + 1] = ((interpolated shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(data: ByteArray, index: Int): Int {
        val low = data[index].toInt() and 0xFF
        val high = data[index + 1].toInt()
        return (low or (high shl 8))
    }
}