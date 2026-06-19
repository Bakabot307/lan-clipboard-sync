package com.gnaht.phoneclipboardsync

import android.app.Application
import com.gnaht.phoneclipboardsync.data.AppPreferences
import com.gnaht.phoneclipboardsync.data.ClipboardSyncController

class LanClipboardApplication : Application() {
    lateinit var controller: ClipboardSyncController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = ClipboardSyncController(
            appContext = this,
            preferences = AppPreferences(this),
        )
    }
}
