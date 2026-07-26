package com.app.truvend_cam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
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
import com.app.truvend_cam.data.RelaySettings
import com.app.truvend_cam.dvr.LocalOnlySiteConfigSource
import com.app.truvend_cam.dvr.RecordControlClient
import com.app.truvend_cam.dvr.SegmentationScheduler
import com.app.truvend_cam.forwarder.ForwarderServer
import com.app.truvend_cam.network.IsapiClient
import com.app.truvend_cam.ui.RelayActivity
import com.app.truvend_cam.util.AppLog
import java.net.Socket
import kotlinx.coroutines.runBlocking

/**
 * Foreground service hosting RTSP + HTTP/ISAPI [ForwarderServer]s. Survives
 * screen-off and process backgrounding via a persistent notification + partial
 * wake lock.
 *
 * Also hosts [SegmentationScheduler] for wake-lock / boot lifetime only — the
 * TCP accept/pipe path is untouched; all DVR-management logic stays in `dvr/`.
 */
class ForwarderService : Service() {

    private var rtspForwarder: ForwarderServer? = null
    private var httpForwarder: ForwarderServer? = null
    private var segmentationScheduler: SegmentationScheduler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAtElapsed: Long = 0L

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (rtspForwarder != null || httpForwarder != null) {
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

        if (rtspForwarder != null || httpForwarder != null) {
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
        // HTTP listen is fixed at 8080; never let a mis-saved RTSP port collide with it.
        val rtspListenPort = if (relay.listenPort == RelaySettings.DEFAULT_HTTP_LISTEN_PORT) {
            AppLog.w(
                TAG,
                "RTSP listen port ${relay.listenPort} collides with HTTP/ISAPI — " +
                    "falling back to ${RelaySettings.DEFAULT_LISTEN_PORT}",
            )
            RelaySettings.DEFAULT_LISTEN_PORT
        } else {
            relay.listenPort
        }
        ensureNotificationChannel()
        startAsForeground(buildNotification(0))

        acquireWakeLock()
        startedAtElapsed = SystemClock.elapsedRealtime()

        val wifiBinder = app.wifiBinder
        // Keep LAN binding alive for the service lifetime. Without a callback,
        // DVR hops often fail until Live view has bound the process once.
        wifiBinder.bindToLanSync()
        networkCallback = wifiBinder.registerNetworkRegain {
            AppLog.i(TAG, "LAN network regained — rebinding process")
            wifiBinder.bindToLanSync()
        }

        val prepareDvrSocket: (Socket) -> Unit = { socket: Socket ->
            // Bind outbound DVR socket to LAN when available (cellular dual-stack).
            // Later: also call VpnService.protect(socket) here.
            val network = wifiBinder.getBoundNetwork() ?: wifiBinder.bindToLanSync()
            network?.let { runCatching { wifiBinder.bindSocket(it, socket) } }
        }

        // RTSP: tunnel → box:listenPort → DVR:rtspPort (typically 8554 → 554)
        // Keep the original 60s socket timeout — unchanged from pre-HTTP behaviour.
        rtspForwarder = ForwarderServer(
            dvrHost = config.host,
            dvrPort = config.rtspPort,
            listenPort = rtspListenPort,
            socketTimeoutMs = ForwarderServer.DEFAULT_SOCKET_TIMEOUT_MS,
            prepareDvrSocket = prepareDvrSocket,
        ).also { it.start() }

        // HTTP/ISAPI: tunnel → box:8080 → DVR:httpPort (typically 8080 → 80)
        // No idle timeout — long downloads idle the reverse direction after the request.
        httpForwarder = ForwarderServer(
            dvrHost = config.host,
            dvrPort = config.httpPort,
            listenPort = RelaySettings.DEFAULT_HTTP_LISTEN_PORT,
            socketTimeoutMs = ForwarderServer.NO_SOCKET_TIMEOUT_MS,
            prepareDvrSocket = prepareDvrSocket,
        ).also { it.start() }

        instance = this
        app.configRepository.setRelayEnabled(true)
        startSegmentation(app)
        handler.post(notificationUpdater)
        AppLog.i(
            TAG,
            "Relay service started → RTSP ${config.host}:${config.rtspPort} " +
                "+ HTTP ${config.host}:${config.httpPort}",
        )

        return START_STICKY
    }

    private fun startSegmentation(app: TruvendApp) {
        if (segmentationScheduler?.isRunning() == true) return
        segmentationScheduler = SegmentationScheduler(
            configRepository = app.configRepository,
            recordControl = RecordControlClient(app.isapiClient),
            siteConfigSource = LocalOnlySiteConfigSource(),
            trackIdsProvider = { resolveMainTrackIds(app) },
        ).also { it.start() }
    }

    /**
     * Prefer in-memory discovery from live/setup. After reboot the cache is empty —
     * discover once on the worker thread so multi-channel sites segment all mains.
     */
    private fun resolveMainTrackIds(app: TruvendApp): List<Int> {
        if (app.cachedChannels.isNotEmpty()) {
            return SegmentationScheduler.mainTrackIds(app.cachedChannels)
        }
        val config = app.configRepository.load() ?: return SegmentationScheduler.mainTrackIds(emptyList())
        return runBlocking {
            when (val result = app.isapiClient.discoverChannels(config)) {
                is IsapiClient.Result.Ok -> {
                    app.cachedChannels = result.value
                    SegmentationScheduler.mainTrackIds(result.value)
                }
                is IsapiClient.Result.Err -> {
                    AppLog.w(TAG, "Channel discovery for segmentation failed: ${result.error.userMessage}")
                    SegmentationScheduler.mainTrackIds(emptyList())
                }
            }
        }
    }

    private fun stopSegmentation() {
        runCatching { segmentationScheduler?.stop() }
        segmentationScheduler = null
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
        stopSegmentation()
        releaseNetworkCallback()
        stopForwarders()
        instance = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopForwarders() {
        runCatching { rtspForwarder?.stop() }
        runCatching { httpForwarder?.stop() }
        rtspForwarder = null
        httpForwarder = null
    }

    private fun releaseNetworkCallback() {
        val app = application as? TruvendApp ?: return
        networkCallback?.let { app.wifiBinder.unregisterCallback(it) }
        networkCallback = null
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
        stopSegmentation()
        releaseNetworkCallback()
        stopForwarders()
        if (instance === this) instance = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        AppLog.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun activeConnections(): Int =
        (rtspForwarder?.activeConnections() ?: 0) + (httpForwarder?.activeConnections() ?: 0)

    fun totalConnections(): Long =
        (rtspForwarder?.totalConnections() ?: 0L) + (httpForwarder?.totalConnections() ?: 0L)

    fun uptimeMillis(): Long {
        if (startedAtElapsed == 0L) return 0L
        return SystemClock.elapsedRealtime() - startedAtElapsed
    }

    fun isRelayRunning(): Boolean =
        rtspForwarder?.isRunning() == true || httpForwarder?.isRunning() == true

    /** True when the RTSP listener is bound (primary relay readiness). */
    fun isListening(): Boolean = rtspForwarder?.isListening() == true

    fun isHttpListening(): Boolean = httpForwarder?.isListening() == true

    fun listenPort(): Int? = rtspForwarder?.listenPort()

    fun httpListenPort(): Int? = httpForwarder?.listenPort()

    fun dvrHost(): String? = rtspForwarder?.dvrHost() ?: httpForwarder?.dvrHost()

    fun dvrPort(): Int? = rtspForwarder?.dvrPort()

    fun httpDvrPort(): Int? = httpForwarder?.dvrPort()

    fun lastError(): String? {
        val rtsp = rtspForwarder?.lastError()
        val http = httpForwarder?.lastError()
        return when {
            !rtsp.isNullOrBlank() && !http.isNullOrBlank() -> "RTSP: $rtsp | HTTP: $http"
            !rtsp.isNullOrBlank() -> rtsp
            !http.isNullOrBlank() -> "HTTP: $http"
            else -> null
        }
    }

    fun socatEquivalent(): String? = rtspForwarder?.socatEquivalent()

    fun httpSocatEquivalent(): String? = httpForwarder?.socatEquivalent()

    fun segmentationSummary(): String? = segmentationScheduler?.lastCycleSummary

    fun segmentationRunning(): Boolean = segmentationScheduler?.isRunning() == true

    fun requestSegmentationCycleNow() {
        segmentationScheduler?.runCycleNow()
    }

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
        val listening = isListening() || isHttpListening()
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

        fun isHttpListening(): Boolean = instance?.isHttpListening() == true

        fun activeConnections(): Int = instance?.activeConnections() ?: 0

        fun totalConnections(): Long = instance?.totalConnections() ?: 0L

        fun uptimeMillis(): Long = instance?.uptimeMillis() ?: 0L

        fun listenPort(): Int? = instance?.listenPort()

        fun httpListenPort(): Int? = instance?.httpListenPort()

        fun dvrHost(): String? = instance?.dvrHost()

        fun dvrPort(): Int? = instance?.dvrPort()

        fun httpDvrPort(): Int? = instance?.httpDvrPort()

        fun lastError(): String? = instance?.lastError()

        fun socatEquivalent(): String? = instance?.socatEquivalent()

        fun httpSocatEquivalent(): String? = instance?.httpSocatEquivalent()

        fun segmentationSummary(): String? = instance?.segmentationSummary()

        fun segmentationRunning(): Boolean = instance?.segmentationRunning() == true

        fun requestSegmentationCycleNow() {
            instance?.requestSegmentationCycleNow()
        }

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
