package org.mhrri.wavestudio

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import java.util.Locale

internal const val SETTINGS_PREFS_NAME = "oscope_settings"
internal const val KEY_HIDE_STARTUP_NOTE = "hide_startup_note"
internal const val KEY_APP_LANGUAGE = "app_language"
internal const val LANG_ZH = "zh"
internal const val LANG_EN = "en"
internal const val KEY_TRIGGER_MODE_NAME = "trigger_mode_name"
internal const val KEY_TRIGGER_NORMAL_ENABLED = "trigger_normal_enabled"
internal const val KEY_EQ_DRAGGABLE = "eq_draggable"
internal const val KEY_RAW_WAVE_HEIGHT_DP = "raw_wave_height_dp"
internal const val KEY_FILTERED_WAVE_HEIGHT_DP = "filtered_wave_height_dp"

private fun defaultLanguageFromSystem(context: android.content.Context): String {
    val lang = context.resources.configuration.locales.get(0)?.language ?: Locale.getDefault().language
    return if (lang.startsWith("zh")) LANG_ZH else LANG_EN
}

internal fun readSavedAppLanguage(context: android.content.Context): String {
    val prefs = context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_APP_LANGUAGE, null)
    return when (saved) {
        LANG_ZH -> LANG_ZH
        LANG_EN -> LANG_EN
        else -> defaultLanguageFromSystem(context)
    }
}

private fun wrapContextWithAppLanguage(base: android.content.Context): android.content.Context {
    val locale = Locale.forLanguageTag(readSavedAppLanguage(base))
    Locale.setDefault(locale)
    val config = android.content.res.Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return base.createConfigurationContext(config)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(wrapContextWithAppLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivityHolder.activity = this

        // Enable edge-to-edge: allow content to draw under system bars
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK

            // Allow drawing into the display cutout area (camera notch) in landscape (API 28+)
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        setContent {
            MaterialTheme {
                // IMPORTANT: In edge-to-edge mode, don't let Scaffold auto-apply system bar paddings,
                // otherwise you'll see a top bar (often white) around the cutout area.
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    OscopeApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (MainActivityHolder.activity === this) MainActivityHolder.activity = null
        super.onDestroy()
    }
}

internal object MainActivityHolder {
    var activity: ComponentActivity? = null
}
