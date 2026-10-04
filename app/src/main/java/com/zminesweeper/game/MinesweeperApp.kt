package com.zminesweeper.game

import android.app.Application
import android.util.Log
import android.widget.Toast

/**
 * Глобальный обработчик неперехваченных исключений.
 * Логирует краш и показывает пользователю Toast с краткой информацией,
 * чтобы пользователю было видно, что приложение упало и почему.
 */
class MinesweeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Установка глобального обработчика.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("MinesweeperCrash", "Uncaught exception on ${thread.name}", throwable)
                val msg = throwable.message ?: throwable.javaClass.simpleName
                val toastText = "Сапёр крашнулся: $msg"
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
}
