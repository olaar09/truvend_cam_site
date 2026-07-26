package com.app.truvend_cam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.app.truvend_cam.BuildConfig
import com.app.truvend_cam.R
import com.app.truvend_cam.TruvendApp
import com.app.truvend_cam.data.RelaySettings
import com.app.truvend_cam.databinding.ActivityRelayBinding
import com.app.truvend_cam.service.ForwarderService
import com.app.truvend_cam.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Installer-facing relay status: running state, RTSP + HTTP forward details,
 * socat-equivalent paths, connections, DVR reachability, battery warning.
 */
class RelayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelayBinding
    private val app get() = application as TruvendApp
    private val handler = Handler(Looper.getMainLooper())
    private var updatingSwitch = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i(TAG, "POST_NOTIFICATIONS granted=$granted")
            if (binding.switchRelay.isChecked) {
                startRelay()
            }
        }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val dvrProbeRunnable = object : Runnable {
        override fun run() {
            probeDvr()
            handler.postDelayed(this, DVR_PROBE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRelayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefill()
        binding.switchRelay.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) {
                ensureNotificationPermissionThenStart()
            } else {
                ForwarderService.stop(this)
                refreshStatus()
            }
        }
        binding.btnBatteryFix.setOnClickListener { requestBatteryExemption() }
        binding.btnSaveListenPort.setOnClickListener { saveListenPort() }
        binding.btnChangeDvr.setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_FORCE_SETUP, true),
            )
        }
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // Debug-only: fire stop→start immediately for gap / schedule tests.
        if (BuildConfig.DEBUG) {
            binding.btnSegmentNow.visibility = View.VISIBLE
            binding.btnSegmentNow.setOnClickListener {
                if (!ForwarderService.segmentationRunning()) {
                    Toast.makeText(this, R.string.relay_segment_now_need_relay, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ForwarderService.requestSegmentationCycleNow()
                Toast.makeText(this, R.string.relay_segment_now_started, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefill()
        refreshStatus()
        updateBatteryWarning()
        handler.post(refreshRunnable)
        handler.post(dvrProbeRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(dvrProbeRunnable)
        super.onPause()
    }

    private fun prefill() {
        val config = app.configRepository.load()
        val relay = app.configRepository.loadRelaySettings()
        if (config != null) {
            binding.dvrAddress.text = getString(
                R.string.relay_dvr_address,
                config.host,
                config.rtspPort,
                config.httpPort,
            )
        } else {
            binding.dvrAddress.text = getString(R.string.relay_dvr_not_configured)
        }
        binding.inputListenPort.setText(relay.listenPort.toString())
    }

    private fun refreshStatus() {
        val running = ForwarderService.isRunning()
        val listening = ForwarderService.isListening()
        val httpListening = ForwarderService.isHttpListening()
        updatingSwitch = true
        binding.switchRelay.isChecked = running
        updatingSwitch = false

        // Overall green when both forwards are up; amber/red if only one or none.
        binding.statusLabel.text = when {
            listening && httpListening -> getString(R.string.relay_status_running)
            running -> getString(R.string.relay_listen_no)
            else -> getString(R.string.relay_status_stopped)
        }
        binding.statusLabel.setTextColor(
            getColor(
                when {
                    listening && httpListening -> R.color.accent
                    running -> R.color.error
                    else -> R.color.text_secondary
                },
            ),
        )

        refreshRelayDetails(running, listening, httpListening)

        binding.activeConnections.text = getString(
            R.string.relay_active_connections,
            ForwarderService.activeConnections(),
        )
        binding.totalConnections.text = getString(
            R.string.relay_total_connections,
            ForwarderService.totalConnections(),
        )
        binding.uptimeText.text = getString(
            R.string.relay_uptime,
            formatUptime(ForwarderService.uptimeMillis()),
        )
        refreshSegmentationStatus()
    }

    private fun refreshSegmentationStatus() {
        val settings = app.configRepository.loadSegmentationSettings()
        if (!settings.enabled) {
            binding.segmentationStatus.text = getString(R.string.relay_segmentation_off)
            return
        }
        val last = ForwarderService.segmentationSummary()
        binding.segmentationStatus.text = if (last.isNullOrBlank()) {
            getString(R.string.relay_segmentation_waiting, settings.clampedIntervalHours)
        } else {
            getString(R.string.relay_segmentation_last, settings.clampedIntervalHours, last)
        }
    }

    private fun refreshRelayDetails(running: Boolean, listening: Boolean, httpListening: Boolean) {
        val config = app.configRepository.load()
        val relay = app.configRepository.loadRelaySettings()
        val listenPort = ForwarderService.listenPort() ?: relay.listenPort
        val httpListenPort =
            ForwarderService.httpListenPort() ?: RelaySettings.DEFAULT_HTTP_LISTEN_PORT
        val dvrHost = ForwarderService.dvrHost() ?: config?.host
        val dvrRtspPort = ForwarderService.dvrPort() ?: config?.rtspPort ?: 554
        val dvrHttpPort = ForwarderService.httpDvrPort() ?: config?.httpPort ?: 80

        binding.listenStatus.text = when {
            listening && httpListening -> getString(R.string.relay_listen_yes)
            running -> getString(R.string.relay_listen_no)
            else -> getString(R.string.relay_listen_stopped)
        }
        binding.listenStatus.setTextColor(
            getColor(
                when {
                    listening && httpListening -> R.color.accent
                    running -> R.color.error
                    else -> R.color.text_secondary
                },
            ),
        )

        if (dvrHost != null) {
            binding.rtspForwardLine.text = getString(
                when {
                    listening -> R.string.relay_forward_rtsp_ok
                    running -> R.string.relay_forward_rtsp_down
                    else -> R.string.relay_forward_rtsp_off
                },
                listenPort,
                dvrHost,
                dvrRtspPort,
            )
            binding.rtspForwardLine.setTextColor(
                getColor(
                    when {
                        listening -> R.color.accent
                        running -> R.color.error
                        else -> R.color.text_secondary
                    },
                ),
            )
            binding.httpForwardLine.text = getString(
                when {
                    httpListening -> R.string.relay_forward_http_ok
                    running -> R.string.relay_forward_http_down
                    else -> R.string.relay_forward_http_off
                },
                httpListenPort,
                dvrHost,
                dvrHttpPort,
            )
            binding.httpForwardLine.setTextColor(
                getColor(
                    when {
                        httpListening -> R.color.accent
                        running -> R.color.error
                        else -> R.color.text_secondary
                    },
                ),
            )
        } else {
            binding.rtspForwardLine.text = getString(R.string.relay_forward_rtsp_unknown)
            binding.httpForwardLine.text = getString(R.string.relay_forward_http_unknown)
            binding.rtspForwardLine.setTextColor(getColor(R.color.text_secondary))
            binding.httpForwardLine.setTextColor(getColor(R.color.text_secondary))
        }

        val socat = ForwarderService.socatEquivalent()
            ?: if (dvrHost != null) {
                "TCP-LISTEN:$listenPort,fork,reuseaddr → TCP:$dvrHost:$dvrRtspPort"
            } else {
                "TCP-LISTEN:$listenPort,fork,reuseaddr → TCP:(DVR not set)"
            }
        binding.socatLine.text = getString(R.string.relay_socat_line, socat)

        val socatHttp = ForwarderService.httpSocatEquivalent()
            ?: if (dvrHost != null) {
                "TCP-LISTEN:$httpListenPort,fork,reuseaddr → TCP:$dvrHost:$dvrHttpPort"
            } else {
                "TCP-LISTEN:$httpListenPort,fork,reuseaddr → TCP:(DVR not set)"
            }
        binding.socatHttpLine.text = getString(R.string.relay_socat_http_line, socatHttp)

        binding.listenAddress.text = getString(
            R.string.relay_listen_address,
            listenPort,
            httpListenPort,
        )

        val ips = localIpv4Addresses()
        binding.deviceIps.text = if (ips.isEmpty()) {
            getString(R.string.relay_device_ips_none)
        } else {
            getString(R.string.relay_device_ips, ips.joinToString("\n") { "  • $it" })
        }

        val error = ForwarderService.lastError()
        if (error.isNullOrBlank()) {
            binding.lastError.visibility = View.GONE
        } else {
            binding.lastError.visibility = View.VISIBLE
            binding.lastError.text = getString(R.string.relay_last_error, error)
        }
    }

    private fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: "iface"
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        result += "$host ($name)"
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed listing IPs: ${e.javaClass.simpleName}")
        }
        return result.sorted()
    }

    private fun probeDvr() {
        val config = app.configRepository.load() ?: run {
            binding.dvrReachable.text = getString(R.string.relay_dvr_reachable_rtsp_unknown)
            binding.dvrReachableHttp.text = getString(R.string.relay_dvr_reachable_http_unknown)
            return
        }
        lifecycleScope.launch {
            val (rtspOk, httpOk) = withContext(Dispatchers.IO) {
                val rtsp = async { tcpReachable(config.host, config.rtspPort) }
                val http = async { tcpReachable(config.host, config.httpPort) }
                rtsp.await() to http.await()
            }
            binding.dvrReachable.text = if (rtspOk) {
                getString(R.string.relay_dvr_reachable_rtsp_yes)
            } else {
                getString(R.string.relay_dvr_reachable_rtsp_no)
            }
            binding.dvrReachable.setTextColor(
                getColor(if (rtspOk) R.color.accent else R.color.error),
            )
            binding.dvrReachableHttp.text = if (httpOk) {
                getString(R.string.relay_dvr_reachable_http_yes)
            } else {
                getString(R.string.relay_dvr_reachable_http_no)
            }
            binding.dvrReachableHttp.setTextColor(
                getColor(if (httpOk) R.color.accent else R.color.error),
            )
        }
    }

    private fun tcpReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 3_000
                socket.connect(InetSocketAddress(host, port), 3_000)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startRelay()
    }

    private fun startRelay() {
        val config = app.configRepository.load()
        if (config == null || !config.isComplete()) {
            Toast.makeText(this, R.string.relay_need_dvr_config, Toast.LENGTH_LONG).show()
            updatingSwitch = true
            binding.switchRelay.isChecked = false
            updatingSwitch = false
            return
        }
        saveListenPort(restartIfRunning = false)
        if (!isIgnoringBatteryOptimizations()) {
            requestBatteryExemption()
        }
        ForwarderService.start(this)
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun saveListenPort(restartIfRunning: Boolean = true) {
        val port = binding.inputListenPort.text?.toString()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, R.string.relay_invalid_port, Toast.LENGTH_SHORT).show()
            return
        }
        if (port == RelaySettings.DEFAULT_HTTP_LISTEN_PORT) {
            Toast.makeText(
                this,
                getString(
                    R.string.relay_port_reserved_http,
                    RelaySettings.DEFAULT_HTTP_LISTEN_PORT,
                ),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val wasRunning = ForwarderService.isRunning()
        val current = app.configRepository.loadRelaySettings()
        app.configRepository.saveRelaySettings(
            RelaySettings(listenPort = port, enabled = current.enabled || wasRunning),
        )
        if (restartIfRunning && wasRunning) {
            ForwarderService.stop(this)
            handler.postDelayed({
                ForwarderService.start(this)
                refreshStatus()
            }, 400)
            Toast.makeText(this, R.string.relay_port_saved_restarted, Toast.LENGTH_SHORT).show()
        } else if (restartIfRunning) {
            Toast.makeText(this, R.string.relay_port_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBatteryWarning() {
        val exempt = isIgnoringBatteryOptimizations()
        binding.batteryWarning.visibility = if (exempt) View.GONE else View.VISIBLE
        binding.btnBatteryFix.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "Battery exemption intent failed: ${e.javaClass.simpleName}")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "—"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    companion object {
        private const val TAG = "RelayActivity"
        private const val REFRESH_MS = 1_000L
        private const val DVR_PROBE_MS = 5_000L
    }
}
