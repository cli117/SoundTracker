package com.soundtracker.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Legacy lifecycle service stub kept for manifest compatibility.
 * Automatic exit deletion is disabled per user preference; all storage cleanup is user-controlled.
 */
class SessionLifecycleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
