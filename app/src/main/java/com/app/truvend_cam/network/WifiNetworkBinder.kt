package com.app.truvend_cam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.app.truvend_cam.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Binds process/sockets to Wi‑Fi so LAN DVR traffic is not routed over cellular
 * when the camera Wi‑Fi has no internet (unvalidated network).
 */
class WifiNetworkBinder(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val boundWifi = AtomicReference<Network?>(null)
    private var callback: ConnectivityManager.NetworkCallback? = null

    sealed class LanStatus {
        data object WifiAvailable : LanStatus()
        data object CellularOnly : LanStatus()
        data object NoNetwork : LanStatus()
        data object Unknown : LanStatus()
    }

    fun currentLanStatus(): LanStatus {
        val networks = connectivity.allNetworks
        var hasWifi = false
        var hasCellular = false
        for (network in networks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                hasWifi = true
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                hasCellular = true
            }
        }
        return when {
            hasWifi -> LanStatus.WifiAvailable
            hasCellular -> LanStatus.CellularOnly
            networks.isEmpty() -> LanStatus.NoNetwork
            else -> LanStatus.Unknown
        }
    }

    /**
     * Request Wi‑Fi (or Ethernet) and bind the process to it.
     * Returns the Network to use for OkHttp / sockets, or null if unavailable.
     */
    suspend fun bindToWifi(): Network? {
        findLanNetwork()?.let { network ->
            try {
                connectivity.bindProcessToNetwork(network)
                boundWifi.set(network)
                AppLog.i(TAG, "Bound to existing LAN network")
                return network
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed binding existing LAN network", e)
            }
        }

        return suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.i(TAG, "Wi‑Fi network available, binding process")
                    try {
                        connectivity.bindProcessToNetwork(network)
                        boundWifi.set(network)
                        if (cont.isActive) cont.resume(network)
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Failed to bind process to Wi‑Fi", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onLost(network: Network) {
                    AppLog.w(TAG, "Wi‑Fi network lost")
                    if (boundWifi.get() == network) {
                        boundWifi.set(null)
                        try {
                            connectivity.bindProcessToNetwork(null)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            callback = cb
            try {
                connectivity.requestNetwork(request, cb)
            } catch (e: Exception) {
                AppLog.e(TAG, "requestNetwork failed", e)
                if (cont.isActive) cont.resume(null)
            }

            // Also try Ethernet for TV boxes on wired LAN
            try {
                val ethRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                connectivity.registerNetworkCallback(
                    ethRequest,
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            AppLog.i(TAG, "Ethernet available, binding process")
                            try {
                                connectivity.bindProcessToNetwork(network)
                                boundWifi.set(network)
                                if (cont.isActive) cont.resume(network)
                            } catch (_: Exception) {
                            }
                        }
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Non-blocking bind for services that cannot wait on a coroutine. */
    fun bindToLanSync(): Network? {
        val network = findLanNetwork() ?: return boundWifi.get()
        return try {
            connectivity.bindProcessToNetwork(network)
            boundWifi.set(network)
            AppLog.i(TAG, "Sync-bound to LAN network")
            network
        } catch (e: Exception) {
            AppLog.e(TAG, "Sync bind to LAN failed", e)
            null
        }
    }

    private fun findLanNetwork(): Network? {
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return network
            }
        }
        return boundWifi.get()
    }

    fun bindSocket(network: Network, socket: java.net.Socket) {
        network.bindSocket(socket)
    }

    fun getBoundNetwork(): Network? = boundWifi.get()

    fun unbind() {
        try {
            connectivity.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        boundWifi.set(null)
        callback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
    }

    fun registerNetworkRegain(onRegain: () -> Unit): ConnectivityManager.NetworkCallback {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivity.getNetworkCapabilities(network) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    try {
                        connectivity.bindProcessToNetwork(network)
                        boundWifi.set(network)
                    } catch (_: Exception) {
                    }
                    onRegain()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivity.registerNetworkCallback(request, cb)
        return cb
    }

    fun unregisterCallback(cb: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "WifiNetworkBinder"
    }
}
