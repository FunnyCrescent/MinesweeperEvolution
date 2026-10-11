package com.zminesweeper.game

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Базовый Activity с поддержкой выбора языка.
 * Все Activity наследуются от этого класса — язык применяется автоматически.
 */
open class LocaleAwareActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val save = SaveManager(newBase)
        val lang = save.language()
        val locale = when (lang) {
            "ru" -> Locale("ru")
            "en" -> Locale("en")
            "es" -> Locale("es")
            else -> null
        }
        if (locale != null) {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            applyOverrideConfiguration(config)
        }
        super.attachBaseContext(newBase)
    }
}
