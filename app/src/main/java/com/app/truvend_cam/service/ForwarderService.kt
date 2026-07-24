package com.app.truvend_cam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.app.truvend_cam.R
import com.app.truvend_cam.TruvendApp
import com.app.truvend_cam.data.ConfigRepository
import com.app.truvend_cam.forwarder.ForwarderServer
import com.app.truvend_cam.ui.RelayActivity
import com.app.truvend_cam.util.AppLog
import java.net.Socket

/**
 * Foreground service hosting [ForwarderServer]. Survives screen-off and
 * process backgrounding via a persistent notification + partial wake lock.
 */
class ForwarderService : Service() {

    private var forwarder: ForwarderServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAtElapsed: Long = 0L

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (forwarder != null) {
                updateNotification()
                handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "Stop requested")
                // If delivered via startForegroundService, must enter foreground briefly.
                ensureNotificationChannel()
                startAsForeground(buildNotification(0))
                stopRelayAndSelf()
                return START_NOT_STICKY
            }
        }

        if (forwarder != null) {
            AppLog.i(TAG, "Already running")
            return START_STICKY
        }

        val app = application as TruvendApp
        val config = app.configRepository.load()
        if (config == null || !config.isComplete()) {
            AppLog.w(TAG, "No DVR config — cannot start relay")
            ensureNotificationChannel()
            startAsForeground(buildNotification(0))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val relay = app.configRepository.loadRelaySettings()
        ensureNotificationChannel()
        startAsForeground(buildNotification(0))

        acquireWakeLock()
        startedAtElapsed = SystemClock.elapsedRealtime()

        val wifiBinder = app.wifiBinder
        wifiBinder.bindToLanSync()

        forwarder = ForwarderServer(
            dvrHost = config.host,
            dvrPort = config.rtspPort,
            listenPort = relay.listenPort,
            prepareDvrSocket = { socket: Socket ->
                // Bind outbound DVR socket to LAN when available (cellular dual-stack).
                // Later: also call VpnService.protect(socket) here.
                wifiBinder.getBoundNetwork()?.let { network ->
                    runCatching { wifiBinder.bindSocket(network, socket) }
                }
            },
        ).also { it.start() }

        instance = this
        app.configRepository.setRelayEnabled(true)
        handler.post(notificationUpdater)
        AppLog.i(TAG, "Relay service started → ${config.host}:${config.rtspPort}")

        return START_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopRelayAndSelf() {
        handler.removeCallbacks(notificationUpdater)
        val app = application as TruvendApp
        app.configRepository.setRelayEnabled(false)
        runCatching { forwarder?.stop() }
        forwarder = null
        instance = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraRelay::Forwarder",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(notificationUpdater)
        runCatching { forwarder?.stop() }
        forwarder = null
        if (instance === this) instance = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        AppLog.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun activeConnections(): Int = forwarder?.activeConnections() ?: 0

    fun totalConnections(): Long = forwarder?.totalConnections() ?: 0L

    fun uptimeMillis(): Long {
        if (startedAtElapsed == 0L) return 0L
        return SystemClock.elapsedRealtime() - startedAtElapsed
    }

    fun isRelayRunning(): Boolean = forwarder?.isRunning() == true

    fun isListening(): Boolean = forwarder?.isListening() == true

    fun listenPort(): Int? = forwarder?.listenPort()

    fun dvrHost(): String? = forwarder?.dvrHost()

    fun dvrPort(): Int? = forwarder?.dvrPort()

    fun lastError(): String? = forwarder?.lastError()

    fun socatEquivalent(): String? = forwarder?.socatEquivalent()

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(activeConnections()))
    }

    private fun buildNotification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RelayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ForwarderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val listening = isListening()
        val text = if (listening) {
            getString(R.string.relay_notification_text, active)
        } else {
            getString(R.string.relay_notification_not_listening)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_relay_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.relay_action_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.relay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.relay_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ForwarderService"
        const val ACTION_STOP = "com.app.truvend_cam.action.STOP_RELAY"
        const val CHANNEL_ID = "rtsp_relay"
        const val NOTIFICATION_ID = 8554
        private const val NOTIFICATION_REFRESH_MS = 2_000L

        @Volatile
        private var instance: ForwarderService? = null

        fun isRunning(): Boolean = instance?.isRelayRunning() == true

        fun isListening(): Boolean = instance?.isListening() == true

        fun activeConnections(): Int = instance?.activeConnections() ?: 0

        fun totalConnections(): Long = instance?.totalConnections() ?: 0L

        fun uptimeMillis(): Long = instance?.uptimeMillis() ?: 0L

        fun listenPort(): Int? = instance?.listenPort()

        fun dvrHost(): String? = instance?.dvrHost()

        fun dvrPort(): Int? = instance?.dvrPort()

        fun lastError(): String? = instance?.lastError()

        fun socatEquivalent(): String? = instance?.socatEquivalent()

        fun start(context: Context) {
            val intent = Intent(context, ForwarderService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            ConfigRepository(context).setRelayEnabled(false)
            // Prefer stopService when already running — avoids startForeground race.
            if (instance != null) {
                context.stopService(Intent(context, ForwarderService::class.java))
            } else {
                val intent = Intent(context, ForwarderService::class.java).setAction(ACTION_STOP)
                runCatching { ContextCompat.startForegroundService(context, intent) }
            }
        }
    }
}
