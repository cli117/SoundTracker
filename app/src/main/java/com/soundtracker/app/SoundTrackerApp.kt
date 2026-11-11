package com.soundtracker.app

import android.app.Application
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.storage.SessionWorkspaceManager
import com.soundtracker.app.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoundTrackerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Guarantee zero residual workspace junk from any previously killed process,
        // clean up any legacy extracted model files, and delete already-installed OTA APK packages.
        appScope.launch {
            SessionWorkspaceManager.performColdStartSweep(this@SoundTrackerApp)
            NativeAudioEngine.cleanupLegacyModelCache(this@SoundTrackerApp)
            AppUpdateManager.cleanupInstalledOrStaleApks(this@SoundTrackerApp)
        }
    }
}
