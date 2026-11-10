package com.soundtracker.app

import android.app.Application
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.storage.SessionWorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoundTrackerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Guarantee zero residual workspace junk from any previously killed process,
        // and clean up any legacy extracted model files (new engines mmap directly from APK!).
        appScope.launch {
            SessionWorkspaceManager.performColdStartSweep(this@SoundTrackerApp)
            NativeAudioEngine.cleanupLegacyModelCache(this@SoundTrackerApp)
        }
    }
}
