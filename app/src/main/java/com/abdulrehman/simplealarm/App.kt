package com.abdulrehman.simplealarm

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putString("crash", Log.getStackTraceString(e).take(2500)).commit()
            } catch (ex: Throwable) { }
            old?.uncaughtException(t, e)
        }
    }
}
