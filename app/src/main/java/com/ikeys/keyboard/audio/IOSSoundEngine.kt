package com.ikeys.keyboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural iOS-style keyboard acoustic synthesizer backed by low-latency SoundPool.
 * Generates 3 crisp damped woody click profiles (key, delete, modifier) with zero bundled audio assets.
 */
class IOSSoundEngine(context: Context) {

    enum class SoundType {
        KEY_CLICK,
        DELETE_CLICK,
        MODIFIER_CLICK
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val executor = Executors.newSingleThreadExecutor()

    private var soundPool: SoundPool? = null
    @Volatile private var keySoundId: Int = 0
    @Volatile private var deleteSoundId: Int = 0
    @Volatile private var modifierSoundId: Int = 0
    @Volatile private var isReleased: Boolean = false

    @Volatile var enabled: Boolean = true
    @Volatile var volume: Float = 0.78f

    init {
        initAsync()
    }

    private fun initAsync() {
        executor.execute {
            if (isReleased) return@execute
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val pool = SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(attrs)
                    .build()

                val dir = File(appContext.cacheDir, "ikeys_sfx").apply { mkdirs() }
                val keyFile = File(dir, "tock_key_v1.wav")
                val delFile = File(dir, "tock_del_v1.wav")
                val modFile = File(dir, "tock_mod_v1.wav")

                if (!keyFile.exists()) {
                    writeWav(keyFile, synthesizeTock(durationMs = 17, f1 = 1040.0, f2 = 2180.0, decay = 350.0, gain = 0.82))
                }
                if (!delFile.exists()) {
                    writeWav(delFile, synthesizeTock(durationMs = 19, f1 = 720.0, f2 = 1480.0, decay = 300.0, gain = 0.85))
                }
                if (!modFile.exists()) {
                    writeWav(modFile, synthesizeTock(durationMs = 21, f1 = 560.0, f2 = 1160.0, decay = 270.0, gain = 0.88))
                }

                if (isReleased) {
                    pool.release()
                    return@execute
                }

                keySoundId = pool.load(keyFile.absolutePath, 1)
                deleteSoundId = pool.load(delFile.absolutePath, 1)
                modifierSoundId = pool.load(modFile.absolutePath, 1)
                soundPool = pool
            } catch (_: Throwable) {
                // Gracefully degrade if audio subsystem is unavailable
            }
        }
    }

    fun play(type: SoundType) {
        if (!enabled || isReleased) return
        val pool = soundPool ?: return
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val soundId = when (type) {
            SoundType.KEY_CLICK -> keySoundId
            SoundType.DELETE_CLICK -> deleteSoundId
            SoundType.MODIFIER_CLICK -> modifierSoundId
        }
        if (soundId != 0) {
            val v = volume.coerceIn(0.05f, 1.0f)
            pool.play(soundId, v, v, 1, 0, 1.0f)
        }
    }

    fun release() {
        isReleased = true
        try {
            soundPool?.release()
        } catch (_: Throwable) {
        }
        soundPool = null
        executor.shutdownNow()
    }

    /**
     * Synthesizes a soft, woody damped percussive impulse resembling the iOS keyboard "tock".
     */
    private fun synthesizeTock(
        durationMs: Int,
        f1: Double,
        f2: Double,
        decay: Double,
        gain: Double
    ): ShortArray {
        val sampleRate = 44100
        val sampleCount = (sampleRate * durationMs) / 1000
        val pcm = ShortArray(sampleCount)

        for (i in 0 until sampleCount) {
            val t = i.toDouble() / sampleRate
            // Fast 0.6ms attack avoids harsh digital pop
            val attack = (t / 0.0006).coerceAtMost(1.0)
            // Damped body resonance + subtle initial high-frequency tap transient
            val bodyEnv = exp(-t * decay)
            val transientEnv = exp(-t * 1400.0)

            val body = 0.70 * sin(2.0 * PI * f1 * t) + 0.30 * sin(2.0 * PI * f2 * t)
            val tap = 0.45 * sin(2.0 * PI * (f2 * 1.45) * t) * transientEnv

            val raw = attack * (body * bodyEnv + tap) * gain
            // Warm soft saturation
            val shaped = tanh(raw * 1.35)
            pcm[i] = (shaped * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val sampleRate = 44100
        val dataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
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
            putInt(dataSize)
        }

        val dataBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            dataBuf.putShort(sample)
        }

        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(dataBuf.array())
        }
    }
}
