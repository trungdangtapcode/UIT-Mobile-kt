package vn.edu.uit.mobile.designsystem

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Interface language. English remains the resource default; Vietnamese is the app
 * default until the student chooses otherwise in Settings.
 */
enum class AppLanguage(val tag: String) {
    VIETNAMESE("vi"),
    ENGLISH("en");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: VIETNAMESE
    }
}

/** Persists the interface language in the existing appearance preferences. */
object LanguagePreference {
    private const val FILE = "appearance"
    private const val KEY = "language"

    fun read(context: Context): AppLanguage = AppLanguage.fromTag(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, language: AppLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
    }
}

/**
 * Returns a context whose resources resolve to [language]. Used by Compose screens,
 * widget rendering and background notifications so every surface shares one language.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(language.locale)
    configuration.setLayoutDirection(language.locale)
    return createConfigurationContext(configuration)
}

/**
 * Applies [language] to Compose resources. Compose 1.8 resolves `stringResource`
 * from `LocalContext`, so a localized context and configuration are sufficient.
 */
@Composable
fun AppLocaleProvider(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, language) { base.withAppLanguage(language) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content
    )
}
