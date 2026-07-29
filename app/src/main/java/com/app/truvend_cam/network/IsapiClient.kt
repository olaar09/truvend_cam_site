package com.app.truvend_cam.network

import android.net.Network
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.data.DvrConfig
import com.app.truvend_cam.util.AppLog
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hikvision ISAPI client with HTTP Digest auth.
 * Generic enough to add getSnapshot(channelId) in Phase 2 without restructuring auth.
 *
 * Record-control PUTs used by [com.app.truvend_cam.dvr.RecordControlClient] share
 * the same Digest client + Wi‑Fi binding — they do not touch the TCP forwarder.
 */
class IsapiClient(
    private val wifiBinder: WifiNetworkBinder,
) {

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val error: ConnectionError) : Result<Nothing>()
    }

    suspend fun discoverChannels(config: DvrConfig): Result<List<ChannelInfo>> {
        when (wifiBinder.currentLanStatus()) {
            is WifiNetworkBinder.LanStatus.CellularOnly -> {
                return Result.Err(ConnectionError.CellularOnly())
            }
            is WifiNetworkBinder.LanStatus.NoNetwork -> {
                return Result.Err(ConnectionError.NoResponse(config.host))
            }
            else -> { /* WifiAvailable or Unknown — proceed */ }
        }

        val network = wifiBinder.bindToWifi()
        // Under WireGuard, Network.socketFactory → EPERM; process bind is enough.
        val client = buildClient(
            config,
            if (wifiBinder.hasActiveVpn()) null else network,
        )

        // Primary: /ISAPI/Streaming/channels
        when (val primary = getXml(client, config, "/ISAPI/Streaming/channels")) {
            is Result.Ok -> {
                val parsed = try {
                    IsapiXmlParser.parseStreamingChannels(primary.value)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed parsing Streaming/channels", e)
                    emptyList()
                }
                if (parsed.isNotEmpty()) {
                    AppLog.i(TAG, "Discovered ${parsed.size} streaming channels via ISAPI Streaming/channels")
                    return Result.Ok(parsed)
                }
            }
            is Result.Err -> {
                if (primary.error is ConnectionError.AuthFailed) return primary
                AppLog.w(TAG, "Streaming/channels failed: ${primary.error.userMessage}")
            }
        }

        // Fallback: /ISAPI/System/Video/inputs/channels
        when (val inputs = getXml(client, config, "/ISAPI/System/Video/inputs/channels")) {
            is Result.Ok -> {
                val ids = try {
                    IsapiXmlParser.parseVideoInputChannels(inputs.value)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed parsing Video/inputs/channels", e)
                    emptyList()
                }
                if (ids.isNotEmpty()) {
                    val expanded = IsapiXmlParser.expandInputChannelsToStreaming(ids)
                    AppLog.i(TAG, "Discovered ${ids.size} inputs → ${expanded.size} streams via Video/inputs")
                    return Result.Ok(expanded)
                }
            }
            is Result.Err -> {
                if (inputs.error is ConnectionError.AuthFailed) return inputs
                AppLog.w(TAG, "Video/inputs/channels failed: ${inputs.error.userMessage}")
            }
        }

        // Last resort: probe IDs 101…1601 (channels 1–16)
        AppLog.w(TAG, "ISAPI discovery failed; using probe list 1..16")
        return Result.Ok(IsapiXmlParser.probeChannelList(16))
    }

    /**
     * Phase 2 hook — intentionally unused in Phase 1.
     * GET /ISAPI/Streaming/channels/<id>/picture
     */
    @Suppress("unused")
    fun snapshotUrl(config: DvrConfig, streamingChannelId: Int): String =
        "${config.httpBaseUrl}/ISAPI/Streaming/channels/$streamingChannelId/picture"

    /**
     * PUT empty body to a ContentMgmt record-control path (manual stop/start).
     * Accepts 200 / 204. Digest auth + LAN bind match [discoverChannels].
     */
    fun putRecordControl(config: DvrConfig, path: String): Result<Unit> {
        val client = clientFor(config) ?: return Result.Err(
            when (wifiBinder.currentLanStatus()) {
                is WifiNetworkBinder.LanStatus.CellularOnly -> ConnectionError.CellularOnly()
                else -> ConnectionError.NoResponse(config.host)
            },
        )
        val url = "${config.httpBaseUrl}$path"
        AppLog.d(TAG, "PUT $url")
        val body = ByteArray(0).toRequestBody("application/xml".toMediaTypeOrNull())
        val request = Request.Builder().url(url).put(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 204 -> Result.Ok(Unit)
                    401 -> Result.Err(ConnectionError.AuthFailed())
                    403 -> Result.Err(ConnectionError.HttpRejected(response.code))
                    else -> {
                        AppLog.w(TAG, "PUT $path → HTTP ${response.code}")
                        Result.Err(ConnectionError.HttpRejected(response.code))
                    }
                }
            }
        } catch (e: IOException) {
            AppLog.e(TAG, "IO error PUT $path on ${config.host}", e)
            Result.Err(ConnectionError.NoResponse(config.host))
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error PUT $path", e)
            Result.Err(ConnectionError.Generic(e.message ?: "unknown error"))
        }
    }

    /**
     * GET …/record/control/manual/capabilities — look for a native split/checkpoint
     * if stop→start gap is too large.
     */
    fun getRecordControlCapabilities(config: DvrConfig): Result<String> {
        val client = clientFor(config) ?: return Result.Err(
            when (wifiBinder.currentLanStatus()) {
                is WifiNetworkBinder.LanStatus.CellularOnly -> ConnectionError.CellularOnly()
                else -> ConnectionError.NoResponse(config.host)
            },
        )
        return getXml(client, config, "/ISAPI/ContentMgmt/record/control/manual/capabilities")
    }

    /**
     * Digest client for record-control (worker thread). Process-binds to LAN;
     * skips Network.socketFactory when VPN is up (EPERM under WireGuard).
     */
    private fun clientFor(config: DvrConfig): OkHttpClient? {
        when (wifiBinder.currentLanStatus()) {
            is WifiNetworkBinder.LanStatus.CellularOnly -> return null
            is WifiNetworkBinder.LanStatus.NoNetwork -> return null
            else -> { /* WifiAvailable or Unknown — proceed */ }
        }
        wifiBinder.bindToLanSync()
        val network = if (wifiBinder.hasActiveVpn()) {
            null
        } else {
            wifiBinder.getBoundNetwork()
        }
        return buildClient(config, network)
    }

    private fun getXml(
        client: OkHttpClient,
        config: DvrConfig,
        path: String,
    ): Result<String> {
        val url = "${config.httpBaseUrl}$path"
        AppLog.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) Result.Err(ConnectionError.ParseFailed())
                        else Result.Ok(body)
                    }
                    401, 403 -> {
                        if (response.code == 401) Result.Err(ConnectionError.AuthFailed())
                        else Result.Err(ConnectionError.HttpRejected(response.code))
                    }
                    else -> Result.Err(ConnectionError.HttpRejected(response.code))
                }
            }
        } catch (e: IOException) {
            AppLog.e(TAG, "IO error talking to ${config.host}", e)
            Result.Err(ConnectionError.NoResponse(config.host))
        } catch (e: Exception) {
            AppLog.e(TAG, "Unexpected error", e)
            Result.Err(ConnectionError.Generic(e.message ?: "unknown error"))
        }
    }

    private fun buildClient(config: DvrConfig, network: Network?): OkHttpClient {
        val digest = DigestAuthenticator(config.username, config.password)
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .authenticator(digest)
            .followRedirects(true)

        if (network != null) {
            try {
                builder.socketFactory(network.socketFactory)
            } catch (e: Exception) {
                AppLog.w(TAG, "LAN socketFactory unavailable, using process bind only: ${e.message}")
            }
        }

        return builder.build()
    }

    /**
     * Minimal HTTP Digest authenticator for Hikvision ISAPI.
     * Credentials are held in memory only; never logged.
     */
    private class DigestAuthenticator(
        private val username: String,
        private val password: String,
    ) : Authenticator {
        private val nonceCount = AtomicInteger(0)

        override fun authenticate(route: Route?, response: Response): Request? {
            if (responseCount(response) >= 3) return null
            val header = response.header("WWW-Authenticate") ?: return null
            if (!header.startsWith("Digest", ignoreCase = true)) {
                // Fall back to basic if server asks (rare on Hikvision)
                if (header.startsWith("Basic", ignoreCase = true)) {
                    val cred = Credentials.basic(username, password)
                    return response.request.newBuilder()
                        .header("Authorization", cred)
                        .build()
                }
                return null
            }
            val params = parseAuthParams(header)
            val realm = params["realm"].orEmpty()
            val nonce = params["nonce"].orEmpty()
            val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull()
            val opaque = params["opaque"]
            val algorithm = params["algorithm"] ?: "MD5"
            val method = response.request.method
            val uri = response.request.url.encodedPath
            val nc = String.format("%08x", nonceCount.incrementAndGet())
            val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

            val ha1 = md5("$username:$realm:$password")
            val ha2 = md5("$method:$uri")
            val responseDigest = if (qop != null) {
                md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            } else {
                md5("$ha1:$nonce:$ha2")
            }

            val sb = StringBuilder()
            sb.append("Digest username=\"$username\"")
            sb.append(", realm=\"$realm\"")
            sb.append(", nonce=\"$nonce\"")
            sb.append(", uri=\"$uri\"")
            sb.append(", response=\"$responseDigest\"")
            sb.append(", algorithm=$algorithm")
            if (qop != null) {
                sb.append(", qop=$qop")
                sb.append(", nc=$nc")
                sb.append(", cnonce=\"$cnonce\"")
            }
            if (opaque != null) sb.append(", opaque=\"$opaque\"")

            return response.request.newBuilder()
                .header("Authorization", sb.toString())
                .build()
        }

        private fun responseCount(response: Response): Int {
            var r: Response? = response
            var count = 1
            while (r?.priorResponse != null) {
                count++
                r = r.priorResponse
            }
            return count
        }

        private fun parseAuthParams(header: String): Map<String, String> {
            val body = header.removePrefix("Digest").trim()
            val map = mutableMapOf<String, String>()
            val regex = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
            for (m in regex.findAll(body)) {
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                map[key] = value
            }
            return map
        }

        private fun md5(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.ISO_8859_1))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    companion object {
        private const val TAG = "IsapiClient"
    }
}
