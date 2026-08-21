package com.nanocomm.nanosmart.eventos

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View

enum class FeedbackKind {
    NAVIGATION,
    CONTROL,
    EMERGENCY
}

/** Respuesta visual, háptica y sonora coherente para los controles de la app. */
object InteractionFeedback {
    private var toneGenerator: ToneGenerator? = null

    fun install(view: View, kind: FeedbackKind) {
        view.isSoundEffectsEnabled = false
        val restTranslationZ = view.translationZ
        view.setOnTouchListener { target, event ->
            if (!target.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animatePressed(target, kind, restTranslationZ)
                    signal(target, kind)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animateReleased(target, restTranslationZ)
                }
            }
            false
        }
    }

    fun alert(context: Context) {
        vibrate(context, FeedbackKind.EMERGENCY)
    }

    private fun animatePressed(view: View, kind: FeedbackKind, restTranslationZ: Float) {
        val scale = if (kind == FeedbackKind.EMERGENCY) 0.94f else 0.965f
        val lift = view.resources.displayMetrics.density *
            if (kind == FeedbackKind.EMERGENCY) 12f else 7f
        view.animate()
            .cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(0.94f)
            .translationZ(restTranslationZ + lift)
            .setDuration(75L)
            .start()
    }

    private fun animateReleased(view: View, restTranslationZ: Float) {
        view.animate()
            .cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .translationZ(restTranslationZ)
            .setDuration(150L)
            .start()
    }

    private fun signal(view: View, kind: FeedbackKind) {
        vibrate(view.context, kind)
        playSound(view, kind)
    }

    private fun vibrate(context: Context, kind: FeedbackKind) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (kind) {
                    FeedbackKind.NAVIGATION -> VibrationEffect.createOneShot(18L, 75)
                    FeedbackKind.CONTROL -> VibrationEffect.createOneShot(42L, 150)
                    FeedbackKind.EMERGENCY -> VibrationEffect.createWaveform(
                        longArrayOf(0L, 45L, 45L, 70L),
                        intArrayOf(0, 180, 0, 255),
                        -1
                    )
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    when (kind) {
                        FeedbackKind.NAVIGATION -> 18L
                        FeedbackKind.CONTROL -> 42L
                        FeedbackKind.EMERGENCY -> 120L
                    }
                )
            }
        }
    }

    private fun playSound(view: View, kind: FeedbackKind) {
        val context = view.context
        val soundEnabled = runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SOUND_EFFECTS_ENABLED,
                1
            ) == 1
        }.getOrDefault(true)
        if (!soundEnabled) return

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        if (kind == FeedbackKind.NAVIGATION) {
            view.isSoundEffectsEnabled = true
            view.playSoundEffect(SoundEffectConstants.CLICK)
            view.isSoundEffectsEnabled = false
            return
        }

        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 72).also {
                toneGenerator = it
            }
            when (kind) {
                FeedbackKind.CONTROL -> tone.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                FeedbackKind.EMERGENCY -> tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180)
                FeedbackKind.NAVIGATION -> Unit
            }
        }
    }
}
