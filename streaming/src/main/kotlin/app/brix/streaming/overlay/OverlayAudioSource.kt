package app.brix.streaming.overlay

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [AudioSource] that mixes the microphone with donation-alert PCM for the
 * outgoing stream. It wraps a [MicrophoneSource]; each mic frame is summed with
 * the donation chunks from every currently-armed overlay (if any) at a
 * configurable donation volume, then passed to the encoder. Several overlays
 * can be armed at once — their chunks are summed together before mixing with
 * the mic, so overlapping alerts are all audible rather than one clobbering
 * another. Set as the stream's audio source via `changeAudioSource(...)` when
 * overlay audio "in stream" is enabled.
 */
class OverlayAudioSource(
    private val mic: MicrophoneSource = MicrophoneSource(),
    private val chunkBytes: Int = 4_096,
) : AudioSource(), GetMicrophoneData {

    private val running = AtomicBoolean(false)

    // MUST NOT be a ThreadLocal: start() is called on the caller's thread while
    // inputPCMData() is invoked from MicrophoneSource's own recording thread,
    // where a ThreadLocal reads back null — every frame was then dropped on the
    // floor and the whole stream went silent (mic AND donation) the moment
    // "overlay audio in stream" was switched on.
    @Volatile
    private var out: GetMicrophoneData? = null

    @Volatile
    private var donationPlayers: Set<DonationAudioPlayer> = emptySet()
    @Volatile
    private var donationVolume = 0.7f
    @Volatile
    private var mixing = false

    fun setDonationPlayers(players: Set<DonationAudioPlayer>) {
        donationPlayers = players
    }

    fun setDonationVolume(volume: Float) {
        donationVolume = volume.coerceIn(0f, 1f)
    }

    fun startMixing() {
        mixing = true
        donationPlayers.forEach { it.beginStreamMix() }
    }

    fun stopMixing() {
        mixing = false
    }

    protected override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean,
    ): Boolean {
        return mic.init(sampleRate, isStereo, echoCanceler, noiseSuppressor)
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        if (running.getAndSet(true)) return
        out = getMicrophoneData
        mic.start(this)
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        mic.stop()
        out = null
        mixing = false
    }

    /** Mute state belongs to the user, not to whichever audio source happens to
     *  be installed — so the wrapper forwards it to the mic it owns. Without
     *  this, arming overlay audio silently un-muted a muted mic (the cast in
     *  SrtlaStream.setMicMuted only matched a bare MicrophoneSource). */
    /** Усиление того же микрофона, что и без микшера: иначе включение звука
     *  донатов молча сбрасывало бы настройку уровня. */
    /** Внутренний микрофон микшера — чтобы выбор устройства действовал и при
     *  включённом звуке донатов, а не только без него. */
    fun micSourceForPreference(): MicrophoneSource = mic

    fun setMicGain(gain: Float) {
        mic.microphoneVolume = gain
    }

    fun setMicMuted(muted: Boolean) {
        if (muted) mic.mute() else mic.unMute()
    }

    fun isMicMuted(): Boolean = mic.isMuted()

    override fun isRunning(): Boolean = running.get()

    override fun release() {
        stop()
        mic.release()
    }

    override fun inputPCMData(frame: Frame) {
        val sink = out ?: return
        if (mixing) {
            val chunks = donationPlayers.mapNotNull { it.nextStreamChunk(chunkBytes)?.takeIf { c -> c.isNotEmpty() } }
            if (chunks.isNotEmpty()) {
                sink.inputPCMData(mix(frame.buffer, sumChunks(chunks), donationVolume))
                return
            }
        }
        sink.inputPCMData(frame)
    }

    /** Sums PCM16 samples from several donation chunks (of possibly different
     *  lengths, near the end of a clip) into one combined buffer, clamping to
     *  avoid overflow when multiple alerts overlap. */
    private fun sumChunks(chunks: List<ByteArray>): ByteArray {
        if (chunks.size == 1) return chunks[0]
        val maxLen = chunks.maxOf { it.size }
        val out = ByteArray(maxLen)
        var i = 0
        while (i + 1 < maxLen) {
            var sum = 0
            for (c in chunks) {
                if (i + 1 < c.size) sum += readSample(c, i)
            }
            val clamped = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (clamped and 0xFF).toByte()
            out[i + 1] = ((clamped shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun mix(micBytes: ByteArray, donation: ByteArray, volume: Float): Frame {
        val out = ByteArray(micBytes.size)
        val samples = minOf(micBytes.size, donation.size) / 2
        var i = 0
        while (i < samples) {
            val mi = i * 2
            val micSample = readSample(micBytes, mi)
            val donationSample = readSample(donation, mi)
            // Clamp, never wrap: PCM16 overflow flips the sample to the
            // opposite polarity, which is not "a bit loud" but a hard crack in
            // the broadcast on every loud donation. sumChunks() already clamps;
            // this sum is the one that reaches the encoder.
            val mixed = (micSample + (donationSample * volume)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[mi] = (mixed and 0xFF).toByte()
            out[mi + 1] = ((mixed shr 8) and 0xFF).toByte()
            i++
        }
        while (i * 2 < micBytes.size) {
            out[i * 2] = micBytes[i * 2]
            out[i * 2 + 1] = micBytes[i * 2 + 1]
            i++
        }
        return Frame(out, 0, out.size, System.nanoTime() / 1000)
    }

    private fun readSample(data: ByteArray, index: Int): Short {
        val low = data[index].toInt() and 0xFF
        val high = data[index + 1].toInt() and 0xFF
        return (low or (high shl 8)).toShort()
    }
}