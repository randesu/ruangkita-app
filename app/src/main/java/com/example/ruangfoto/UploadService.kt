package com.ruangfoto.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Network I/O never runs on either of the camera/gallery executors. */
class UploadService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var store: UploadStore
    @Volatile private var transfer: TransferClient? = null
    @Volatile private var stopped = false
    private var idleSince = 0L
    private var lastNotification = 0L

    override fun onCreate() {
        super.onCreate()
        store = UploadStore.get(this)
        stopped = false
        running = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("uploads", "Photo uploads", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Preparing photo uploads")
        if (Build.VERSION.SDK_INT >= 29) startForeground(210, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(210, notification)
        worker.scheduleWithFixedDelay({ tick() }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No sticky background restart: relaunching the visible app recovers its durable queue.
        if (intent?.action == "pause") pause(this)
        return START_NOT_STICKY
    }

    private fun tick() {
        synchronized(uploadLock) { runTick() }
    }

    private fun runTick() {
        if (stopped) return
        try {
            if (store.paused) { stopSelf(); return }
            store.scan()
            val job = store.next()
            if (job == null) {
                progress = ""
                if (!watching && !store.hasPending()) {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince >= 3_000) { stopSelf(); return }
                } else idleSince = 0L
                notifyProgress(store.summary())
                return
            }
            idleSince = 0L
            val settings = store.settings() ?: throw TransferFailure("Configure a transfer server before uploading", true)
            if (settings.destination() != job.destination) return
            val targetSettings = settings.copy(folder = job.remoteFolder.ifBlank { settings.folder })
            val current = transferClient(targetSettings)
            transfer = current
            active = current
            try {
                current.upload(targetSettings, File(job.path), job.size, job.modified, job.name, job.finalizing,
                    { store.finalizing(job) }, { bytes ->
                        progress = "${File(job.path).name}: ${bytes * 100 / maxOf(1L, job.size)}%"
                        notifyProgress(progress)
                    })
                if (!stopped && !store.paused) store.done(job)
            } catch (error: Exception) {
                if (!stopped && !store.paused) {
                    val permanent = error is SSLException || error is IllegalArgumentException || (error as? TransferFailure)?.permanent == true
                    val message = when (error) {
                        is SSLException -> "FTPS certificate verification failed; check the server certificate"
                        is TransferFailure -> error.message ?: "Transfer failed"
                        is InterruptedIOException -> "Network timeout; upload will retry"
                        else -> "Network connection failed; check server and connection"
                    }
                    store.fail(job, message, permanent)
                    if (error is SSLException || error is IllegalArgumentException || (error as? TransferFailure)?.configuration == true) store.paused = true
                    if (error is TransferFailure && error.configuration) progress = error.message ?: "Check transfer settings"
                }
            } finally { transfer = null; if (active === current) active = null; progress = "" }
        } catch (_: Exception) {
            runCatching { store.paused = true }
            progress = "Upload settings/storage unavailable; open FTP settings to correct them"
            notifyProgress(progress)
            stopSelf()
        }
    }

    private fun notifyProgress(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotification >= 1_000) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(210, notification(message))
        }
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, UploadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "uploads").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("RuangFoto uploads").setContentText(message).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.paused = true
        progress = "Android transfer time limit reached; resume from the app"
        stopSelf()
    }
    override fun onDestroy() {
        stopped = true
        running = false
        transfer?.cancel()
        if (active === transfer) active = null
        worker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val uploadLock = Any()
        @Volatile var running = false; private set
        @Volatile var watching = false
        @Volatile var progress = ""; private set
        @Volatile private var active: TransferClient? = null
        fun startFromVisibleApp(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
        }
        fun pause(context: Context) {
            UploadStore.get(context).paused = true
            active?.cancel()
            context.stopService(Intent(context, UploadService::class.java))
        }
    }
}