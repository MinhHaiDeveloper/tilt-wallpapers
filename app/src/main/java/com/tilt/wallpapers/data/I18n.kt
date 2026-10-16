package com.tilt.wallpapers.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

/** Language picked in Cài đặt; SYSTEM follows the phone (Vietnamese phones get Vietnamese, others English). */
@Serializable
enum class AppLanguage { SYSTEM, VI, EN }

enum class Lang { VI, EN }

/**
 * The app's two languages. Text is written in Vietnamese in the code and wrapped in [t]; English comes from
 * [En]. [lang] is Compose state, so every screen recomposes in the new language the moment it changes.
 */
object I18n {
    var lang by mutableStateOf(if (Locale.getDefault().language == "vi") Lang.VI else Lang.EN)
        private set

    /** Resolves [choice] against the phone's language and applies it. */
    fun apply(context: Context, choice: AppLanguage) {
        lang = when (choice) {
            AppLanguage.VI -> Lang.VI
            AppLanguage.EN -> Lang.EN
            AppLanguage.SYSTEM -> if (systemLanguage(context) == "vi") Lang.VI else Lang.EN
        }
    }

    /**
     * The first of Vietnamese / English in the phone's language list (Settings → Languages can hold several,
     * e.g. Japanese, then Vietnamese), ignoring a per-app language set by this app. Neither listed → English.
     */
    private fun systemLanguage(context: Context): String {
        val list = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
            else android.content.res.Resources.getSystem().configuration.locales
        for (i in 0 until list.size()) {
            val l = list.get(i)?.language
            if (l == "vi" || l == "en") return l
        }
        return "en"
    }

    /** The phone's main language is neither Vietnamese nor English ("Theo máy" then explains its pick). */
    fun systemIsOther(context: Context): Boolean {
        val list = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
            else android.content.res.Resources.getSystem().configuration.locales
        return list.get(0)?.language.let { it != "vi" && it != "en" }
    }

    /** What "Theo máy" currently resolves to, for its label. */
    fun systemLang(context: Context): Lang = if (systemLanguage(context) == "vi") Lang.VI else Lang.EN

    /**
     * Android 13+: mirrors the choice into the system's per-app language (Settings → Apps → Tilt → Language),
     * so system strings (app name, wallpaper description) match. Returns false when nothing changed.
     */
    fun syncSystem(context: Context, choice: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val lm = context.getSystemService(LocaleManager::class.java)
        val want = when (choice) {
            AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
            AppLanguage.VI -> LocaleList.forLanguageTags("vi")
            AppLanguage.EN -> LocaleList.forLanguageTags("en")
        }
        if (lm.applicationLocales.toLanguageTags() == want.toLanguageTags()) return false
        lm.applicationLocales = want
        return true
    }

    /** Android 13+: a language picked in the phone's settings for this app, as an [AppLanguage]. */
    fun fromSystem(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < 33) return null
        val tags = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (tags.isEmpty) return AppLanguage.SYSTEM
        return if (tags.get(0).language == "vi") AppLanguage.VI else AppLanguage.EN
    }

    /** "13/10/2026" as the current language writes dates. */
    fun date(dmy: String): String {
        if (lang == Lang.VI) return dmy
        return runCatching {
            val d = SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(dmy)!!
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        }.getOrDefault(dmy)
    }
}

/**
 * Translates Vietnamese UI text. [vi] is the Vietnamese text exactly as written in the code (it is also the
 * lookup key); placeholders are %1\$s, %2\$s… filled from [args].
 */
fun t(vi: String, vararg args: Any?): String {
    val tpl = if (I18n.lang == Lang.EN) En.map[vi] ?: vi else vi
    return if (args.isEmpty()) tpl else String.format(Locale.ROOT, tpl, *args)
}
