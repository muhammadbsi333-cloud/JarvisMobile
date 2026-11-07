package com.jarvis.mobile

import android.app.Application

class JarvisApp : Application() {
    companion object {
        lateinit var instance: JarvisApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        CrashLog.install(this)
    }
}
