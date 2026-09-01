package dev.pumplink.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.PumpRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns keep-alive and teardown for the process-scoped session. Hilt injects
 * the same [PumpSession] the ViewModel sees.
 */
@AndroidEntryPoint
class PumpLinkService : Service() {

    @Inject lateinit var session: PumpSession

    private val binder = LocalBinder()
    private var observeJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class LocalBinder : Binder() {
        fun repository(): PumpRepository = session
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        observeJob = serviceScope.launch {
            combine(session.sessionRequested, session.journal) { requested, journal ->
                requested to journal
            }.collect { (requested, journal) ->
                syncForeground(requested, journal)
            }
        }
        syncForeground(session.sessionRequested.value, session.journal.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncForeground(session.sessionRequested.value, session.journal.value)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session.journal.value.inFlight().isNotEmpty()) return
        session.stop()
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun syncForeground(sessionRequested: Boolean, journal: JournalSnapshot) {
        if (SessionKeepAlive.shouldBeForeground(sessionRequested, journal)) {
            val notification = notification(inFlight = journal.inFlight().isNotEmpty())
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun notification(inFlight: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pump link", NotificationManager.IMPORTANCE_LOW),
        )
        val text = if (inFlight) "Command in flight" else "Pump link active"
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("pump-link")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ID = 17
        const val CHANNEL = "pump-link"

        fun start(context: android.content.Context, foreground: Boolean) {
            val intent = Intent(context, PumpLinkService::class.java)
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
