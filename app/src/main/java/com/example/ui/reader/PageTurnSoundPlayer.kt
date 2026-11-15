package com.example.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates and plays a soft, realistic book-paper page-turn sound via SoundPool.
 * Uses warm band-shaped noise with gentle dual-brush envelopes so there is zero harsh static.
 */
class PageTurnSoundPlayer(
    context: Context,
    scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile
    private var forwardSoundId: Int = 0

    @Volatile
    private var backwardSoundId: Int = 0

    @Volatile
    private var isReleased = false

    init {
        scope.launch {
            val (fwdFile, bwdFile) = withContext(Dispatchers.IO) {
                val cacheDir = appContext.cacheDir
                File(cacheDir, "bs_page_turn_fwd_v1.wav").delete()
                File(cacheDir, "bs_page_turn_bwd_v1.wav").delete()
                val fwd = File(cacheDir, "bs_page_turn_fwd_v2.wav")
                val bwd = File(cacheDir, "bs_page_turn_bwd_v2.wav")
                if (!fwd.exists() || fwd.length() < 1000L) {
                    writePaperTurnWav(fwd, seed = 4219, startFreqHz = 980.0, endFreqHz = 520.0)
                }
                if (!bwd.exists() || bwd.length() < 1000L) {
                    writePaperTurnWav(bwd, seed = 7913, startFreqHz = 880.0, endFreqHz = 460.0)
                }
                fwd to bwd
            }
            if (!isReleased) {
                forwardSoundId = soundPool.load(fwdFile.absolutePath, 1)
                backwardSoundId = soundPool.load(bwdFile.absolutePath, 1)
            }
        }
    }

    fun play(isForward: Boolean) {
        if (isReleased) return
        val id = if (isForward) forwardSoundId else backwardSoundId
        if (id != 0) {
            soundPool.play(id, 0.22f, 0.22f, 1, 0, 1.0f)
        }
    }

    fun release() {
        isReleased = true
        soundPool.release()
    }

    private fun writePaperTurnWav(
        outFile: File,
        seed: Int,
        startFreqHz: Double,
        endFreqHz: Double
    ) {
        val sampleRate = 44100
        val durationSec = 0.24
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        val rng = Random(seed)

        // Dual-stage low-pass pre-filter + biquad bandpass + gentle post-smoother for silky paper air
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var lp1 = 0.0
        var lp2 = 0.0
        var postLp = 0.0
        val q = 0.85

        val rawFloat = DoubleArray(numSamples)
        var maxAbs = 1e-6

        for (i in 0 until numSamples) {
            val t = i.toDouble() / (numSamples - 1).toDouble()

            // Velvet-smooth warm noise excitation
            val white = rng.nextDouble() * 2.0 - 1.0
            lp1 = lp1 * 0.78 + white * 0.22
            lp2 = lp2 * 0.76 + lp1 * 0.24

            // Gentle downward sweep mimicking soft paper gliding across a page
            val fc = startFreqHz + (endFreqHz - startFreqHz) * (t * t)
            val w0 = 2.0 * PI * fc / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)

            val b0 = alpha
            val b1 = 0.0
            val b2 = -alpha
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha

            val filtered = (b0 * lp2 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1
            x1 = lp2
            y2 = y1
            y1 = filtered

            postLp = postLp * 0.55 + filtered * 0.45

            // Smooth raised-cosine window with soft skewed paper-glide swell
            val hann = 0.5 * (1.0 - cos(2.0 * PI * t))
            val glideShape = 0.35 + 0.65 * exp(-((t - 0.46) / 0.28).let { it * it })
            val env = hann * glideShape

            val sampleVal = postLp * env
            rawFloat[i] = sampleVal
            val absVal = kotlin.math.abs(sampleVal)
            if (absVal > maxAbs) maxAbs = absVal
        }

        // Subtle peak normalization (~21% full scale) for whisper-quiet, non-fatiguing feedback
        val scale = 6800.0 / maxAbs
        for (i in 0 until numSamples) {
            samples[i] = (rawFloat[i] * scale).toInt().coerceIn(-32767, 32767).toShort()
        }

        val dataBytes = numSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // Byte rate
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()

        val pcmBuffer = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            pcmBuffer.putShort(s)
        }

        FileOutputStream(outFile).use { fos ->
            fos.write(header)
            fos.write(pcmBuffer.array())
        }
    }
}
