package com.dhrashta.x.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechPace(val multiplier: Float, val labelRes: Int) {
    RELAXED(0.85f, com.dhrashta.x.R.string.audio_pace_relaxed),
    NORMAL(1.0f, com.dhrashta.x.R.string.audio_pace_normal),
    BRISK(1.15f, com.dhrashta.x.R.string.audio_pace_brisk),
}

data class AudioSettings(
    val pace: SpeechPace = SpeechPace.NORMAL,
    val spokenAlertsEnabled: Boolean = false,
)

object AudioSettingsStore {
    private const val PREFS = "dhrashta_audio_settings"
    private const val KEY_PACE = "speech_pace"
    private const val KEY_SPOKEN_ALERTS = "spoken_alerts_enabled"

    private val mutable = MutableStateFlow(AudioSettings())
    val state = mutable.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paceName = prefs.getString(KEY_PACE, SpeechPace.NORMAL.name) ?: SpeechPace.NORMAL.name
        val pace = runCatching { SpeechPace.valueOf(paceName) }.getOrDefault(SpeechPace.NORMAL)
        val alerts = prefs.getBoolean(KEY_SPOKEN_ALERTS, false)
        mutable.value = AudioSettings(pace = pace, spokenAlertsEnabled = alerts)
    }

    fun setPace(context: Context, pace: SpeechPace) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACE, pace.name).apply()
        mutable.value = mutable.value.copy(pace = pace)
    }

    fun setSpokenAlertsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SPOKEN_ALERTS, enabled).apply()
        mutable.value = mutable.value.copy(spokenAlertsEnabled = enabled)
    }

    fun isSpokenAlertsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPOKEN_ALERTS, false)

    fun getPace(context: Context): Float {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACE, SpeechPace.NORMAL.name) ?: SpeechPace.NORMAL.name
        return runCatching { SpeechPace.valueOf(name).multiplier }.getOrDefault(1.0f)
    }
}
