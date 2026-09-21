package com.dhrashta.x.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own UI language, chosen on first launch. On Android 13+ it is applied through the
 * system per-app language setting (also visible in system Settings); on older versions every
 * Activity and Service wraps its base context with [wrap].
 */
object AppLanguage {
    /** One selectable language: BCP-47 [tag], its name in its own script, and in English. */
    data class Option(val tag: String, val nativeName: String, val englishName: String)

    val OPTIONS = listOf(
        Option("en", "English", "English"),
        Option("hi", "हिन्दी", "Hindi"),
        Option("bn", "বাংলা", "Bengali"),
        Option("ta", "தமிழ்", "Tamil"),
        Option("te", "తెలుగు", "Telugu"),
        Option("mr", "मराठी", "Marathi"),
        Option("gu", "ગુજરાતી", "Gujarati"),
        Option("kn", "ಕನ್ನಡ", "Kannada"),
        Option("ml", "മലയാളം", "Malayalam"),
        Option("pa", "ਪੰਜਾਬੀ", "Punjabi"),
        Option("or", "ଓଡ଼ିଆ", "Odia"),
        Option("as", "অসমীয়া", "Assamese"),
        Option("ur", "اردو", "Urdu"),
    )

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"
    private const val KEY_CHOSEN = "chosen"

    // LlmExplainer reads this key; it understands English, Hindi and Bengali names.
    private const val UI_PREFS = "ui_preferences"
    private const val EXPLAINER_LANGUAGE_KEY = "language"

    /** True once the user finished the first-launch language picker. */
    fun isChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHOSEN, false)

    /** Marks the first-launch picker as done. */
    fun markChosen(context: Context) {
        prefs(context).edit().putBoolean(KEY_CHOSEN, true).apply()
    }

    /** The saved language tag, or the best match for the device language if none was saved. */
    fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The user may also change it in system Settings > Apps > Language.
            val system = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (!system.isEmpty) OPTIONS.firstOrNull { it.tag == system[0].language }?.let { return it.tag }
        }
        prefs(context).getString(KEY_TAG, null)?.let { return it }
        val device = Locale.getDefault().language
        return OPTIONS.firstOrNull { it.tag == device }?.tag ?: "en"
    }

    /** The [Option] for [currentTag]. */
    fun current(context: Context): Option = OPTIONS.first { it.tag == currentTag(context) }

    /** Saves and applies [tag]; the Activity is recreated so every screen redraws in the new language. */
    fun apply(activity: Activity, tag: String) {
        prefs(activity).edit().putString(KEY_TAG, tag).commit()
        val explainerName = when (tag) {
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            else -> "English"
        }
        activity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(EXPLAINER_LANGUAGE_KEY, explainerName).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the Activity itself after this call.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    /** Returns [base] configured for the saved language (Android 12 and below; newer versions need nothing). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
