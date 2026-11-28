package com.ikeys.keyboard.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ikeys.keyboard.prefs.HapticStrength
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crisp, zero-UI-blocking haptic feedback controller tuned to mimic Apple's Taptic Engine
 * across Android 8+ devices without mushy motor buzz or main-thread VibratorService stalls.
 */
class IOSHapticEngine(context: Context) {

    private val vibrator: Vibrator? = run {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val hasVibrator: Boolean = vibrator?.hasVibrator() == true
    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingSeq = AtomicInteger(0)

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    var strength: HapticStrength = HapticStrength.LIGHT

    fun keyTap() {
        val current = strength
        if (!hasVibrator || isReleased || current == HapticStrength.OFF) return
        dispatchAsync {
            vibrateCrisp(current.durationMs, current.amplitude, usePredefinedTick = current == HapticStrength.LIGHT)
        }
    }

    fun modifierTap() {
        val current = strength
        if (!hasVibrator || isReleased || current == HapticStrength.OFF) return
        val amp = (current.amplitude + 18).coerceAtMost(210)
        dispatchAsync {
            vibrateCrisp(current.durationMs + 2L, amp, usePredefinedTick = false)
        }
    }

    fun trackpadEngage() {
        if (!hasVibrator || isReleased || strength == HapticStrength.OFF) return
        dispatchAsync {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                } else {
                    vibrateOneShot(16L, 145)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun microTick() {
        val current = strength
        if (!hasVibrator || isReleased || current == HapticStrength.OFF) return
        val amp = (current.amplitude * 0.65f).toInt().coerceAtLeast(24)
        dispatchAsync {
            vibrateOneShot(5L, amp)
        }
    }

    fun release() {
        isReleased = true
        executor.shutdownNow()
    }

    private fun dispatchAsync(block: () -> Unit) {
        val token = pendingSeq.incrementAndGet()
        try {
            executor.execute {
                // Coalesce burst keystrokes so queued vibrator IPCs never pile up
                if (isReleased || pendingSeq.get() != token) return@execute
                block()
            }
        } catch (_: Throwable) {
        }
    }

    private fun vibrateCrisp(durationMs: Long, amplitude: Int, usePredefinedTick: Boolean) {
        try {
            if (usePredefinedTick && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrateOneShot(durationMs, amplitude)
            }
        } catch (_: Throwable) {
        }
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        val vib = vibrator ?: return
        val safeAmp = if (hasAmplitudeControl) {
            amplitude.coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        vib.vibrate(VibrationEffect.createOneShot(durationMs.coerceAtLeast(4L), safeAmp))
    }
}
