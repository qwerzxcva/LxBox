package com.leadaxe.lxbox.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Applies the user-selected UI language by overriding the configuration used
 * for resource lookups inside the composition. Empty [language] means
 * "follow system".
 *
 * We do it with [CompositionLocalProvider] rather than
 * `AppCompatDelegate.setApplicationLocales` because the app deliberately
 * avoids a hard appcompat dependency — the only two locales we ship are
 * `values/` (English) and `values-zh/` (Chinese), and the override only has
 * to survive until the next recomposition.
 */
@Composable
fun LocalizedApp(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    if (language.isBlank()) {
        content()
        return
    }
    val localizedContext = remember(language, baseConfiguration) {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(baseConfiguration).apply {
            setLocale(locale)
        }
        baseContext.createConfigurationContext(config)
    }
    val localizedConfiguration = remember(language, baseConfiguration) {
        Configuration(baseConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
    ) {
        content()
    }
}