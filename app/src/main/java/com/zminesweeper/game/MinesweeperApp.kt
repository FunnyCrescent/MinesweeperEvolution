package com.zminesweeper.game

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.util.Locale

/**
 * Глобальный обработчик неперехваченных исключений.
 * Логирует краш и показывает пользователю Toast с краткой информацией,
 * чтобы пользователю было видно, что приложение упало и почему.
 *
 * Также: применение выбранного языка (v1.2.9.2).
 */
class MinesweeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Загружаем сохранённые размеры кастомного поля.
        val save = SaveManager(this)
        customRows = save.customRows()
        customCols = save.customCols()
        // Применяем выбранный язык.
        applyLanguage(save.language())

        // Установка глобального обработчика.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("MinesweeperCrash", "Uncaught exception on ${thread.name}", throwable)
                val msg = throwable.message ?: throwable.javaClass.simpleName
                val toastText = getString(R.string.crash_message, msg)
                val mainLooper = android.os.Looper.getMainLooper()
                if (thread == Thread.currentThread() && mainLooper.thread == thread) {
                    Toast.makeText(this, toastText, Toast.LENGTH_LONG).show()
                }
            } catch (_: Throwable) {
                // ignore
            }
            // Передаём системе для стандартной обработки.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** Singleton instance — используется для доступа к ресурсам из enum'ов. */
        lateinit var instance: MinesweeperApp
            private set

        /** Кастомные размеры поля, загружаются из SaveManager при старте. */
        @Volatile var customRows: Int = 16
        @Volatile var customCols: Int = 30

        /**
         * Применяет выбранный язык ко всему приложению.
         * lang: "system" (по умолчанию), "ru", "en", "es".
         */
        fun applyLanguage(lang: String) {
            val locale = when (lang) {
                "ru" -> Locale("ru")
                "en" -> Locale("en")
                "es" -> Locale("es")
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Системный язык — берём первый из системных локалей.
                    instance.resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    Locale(instance.resources.configuration.locale.language)
                }
            }
            Locale.setDefault(locale)
            val config = Configuration(instance.resources.configuration)
            config.setLocale(locale)
            // Обновляем ресурсы — это применит язык к строкам.
            instance.createConfigurationContext(config)
            instance.resources.updateConfiguration(config, instance.resources.displayMetrics)
        }
    }
}
