package com.soundtracker.app.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Deferred garbage collector for WeChat share staging files.
 * Runs 30 minutes after a user shares an audio file to WeChat so background uploads
 * finish smoothly even if SoundTracker was closed immediately after tapping Share,
 * while guaranteeing zero permanent storage clutter.
 */
class ShareCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SessionWorkspaceManager.sweepExpiredShareStaging(applicationContext)
        return Result.success()
    }
}
