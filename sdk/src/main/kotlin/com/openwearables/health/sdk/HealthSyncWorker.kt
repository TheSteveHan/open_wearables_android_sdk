package com.openwearables.health.sdk

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.delay

/**
 * WorkManager worker for background health data synchronization.
 *
 * Scheduled as a [PeriodicWorkRequest] by [SyncManager]. The worker does NOT
 * manually schedule the next run — WorkManager handles periodic re-execution.
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_HOST = "host"
        const val KEY_CUSTOM_SYNC_URL = "customSyncUrl"
        /** Keep health FGS notification visible long enough for users / Play demos. */
        private const val MIN_FOREGROUND_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        val host = inputData.getString(KEY_HOST) ?: return Result.failure()
        val customSyncUrl = inputData.getString(KEY_CUSTOM_SYNC_URL)
        val startedAt = SystemClock.elapsedRealtime()

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            android.util.Log.w("HealthSyncWorker", "Could not promote to foreground: ${e.message}")
        }

        val secureStorage = SecureStorage(applicationContext)
        val dispatchers = DefaultDispatcherProvider()
        val provider = createProvider(applicationContext, secureStorage, dispatchers)
        val syncManager = SyncManager(
            applicationContext, secureStorage, provider, dispatchers,
            { android.util.Log.d("HealthSyncWorker", it) }
        )

        val result = try {
            val trackedTypes = secureStorage.getTrackedTypes()
            provider.setTrackedTypes(trackedTypes)

            android.util.Log.d("HealthSyncWorker", "Background sync (provider: ${provider.providerId})")
            syncManager.syncNow(host, customSyncUrl, fullExport = false)

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HealthSyncWorker", "Sync failed", e)
            Result.retry()
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed < MIN_FOREGROUND_MS) {
            delay(MIN_FOREGROUND_MS - elapsed)
        }
        return result
    }

    private fun createProvider(
        context: Context,
        storage: SecureStorage,
        dispatchers: DispatcherProvider
    ): HealthDataProvider {
        val providerId = storage.getProvider()
        val log: (String) -> Unit = { android.util.Log.d("HealthSyncWorker", it) }
        return when (providerId) {
            ProviderIds.GOOGLE -> HealthConnectManager(context, null, dispatchers, log)
            else -> SamsungHealthManager(context, null, dispatchers, log)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val secureStorage = SecureStorage(applicationContext)
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, NotificationConfig.CHANNEL_ID)
            .setContentTitle(secureStorage.getNotificationTitle())
            .setContentText(secureStorage.getNotificationText())
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationConfig.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            ForegroundInfo(NotificationConfig.NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // Recreate if an older LOW-importance channel was cached (OEM hides those)
            val existing = nm.getNotificationChannel(NotificationConfig.CHANNEL_ID)
            if (existing != null && existing.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) {
                nm.deleteNotificationChannel(NotificationConfig.CHANNEL_ID)
            }
            val channel = android.app.NotificationChannel(
                NotificationConfig.CHANNEL_ID,
                NotificationConfig.CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = NotificationConfig.CHANNEL_DESCRIPTION }
            nm.createNotificationChannel(channel)
        }
    }
}
