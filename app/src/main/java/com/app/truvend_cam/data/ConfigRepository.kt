package com.app.truvend_cam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.app.truvend_cam.dvr.SegmentationSettings
import com.app.truvend_cam.util.AppLog

/**
 * Stores DVR credentials in EncryptedSharedPreferences.
 * Password is never written to AppLog.
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        AppLog.e("ConfigRepository", "Encrypted prefs unavailable, falling back to private prefs", e)
        context.applicationContext.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
    }

    fun load(): DvrConfig? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return DvrConfig(
            host = host,
            httpPort = prefs.getInt(KEY_HTTP_PORT, 80),
            rtspPort = prefs.getInt(KEY_RTSP_PORT, 554),
            username = username,
            password = password,
            defaultStreamType = StreamType.fromCode(prefs.getInt(KEY_STREAM_TYPE, StreamType.SUB.code)),
            verified = prefs.getBoolean(KEY_VERIFIED, false),
        )
    }

    fun save(config: DvrConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_HTTP_PORT, config.httpPort)
            .putInt(KEY_RTSP_PORT, config.rtspPort)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putInt(KEY_STREAM_TYPE, config.defaultStreamType.code)
            .putBoolean(KEY_VERIFIED, config.verified)
            .apply()
        AppLog.i("ConfigRepository", "Saved config for host=${config.host} verified=${config.verified}")
    }

    /** Relay settings share the same encrypted store; DVR host/port come from [load]. */
    fun loadRelaySettings(): RelaySettings {
        return RelaySettings(
            listenPort = prefs.getInt(KEY_LISTEN_PORT, RelaySettings.DEFAULT_LISTEN_PORT),
            enabled = prefs.getBoolean(KEY_RELAY_ENABLED, false),
        )
    }

    fun saveRelaySettings(settings: RelaySettings) {
        prefs.edit()
            .putInt(KEY_LISTEN_PORT, settings.listenPort)
            .putBoolean(KEY_RELAY_ENABLED, settings.enabled)
            .apply()
        AppLog.i(
            "ConfigRepository",
            "Saved relay settings listenPort=${settings.listenPort} enabled=${settings.enabled}",
        )
    }

    fun setRelayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
    }

    /**
     * Local copy of per-site segmentation policy. Defaults match the product defaults
     * (enabled, 1 hour). Remote refresh (when built) writes here via
     * [saveSegmentationSettings].
     */
    fun loadSegmentationSettings(): SegmentationSettings {
        return SegmentationSettings(
            enabled = prefs.getBoolean(
                KEY_SEGMENTATION_ENABLED,
                SegmentationSettings.DEFAULT_ENABLED,
            ),
            intervalHours = prefs.getInt(
                KEY_SEGMENTATION_INTERVAL_HOURS,
                SegmentationSettings.DEFAULT_INTERVAL_HOURS,
            ),
        )
    }

    fun saveSegmentationSettings(settings: SegmentationSettings) {
        prefs.edit()
            .putBoolean(KEY_SEGMENTATION_ENABLED, settings.enabled)
            .putInt(KEY_SEGMENTATION_INTERVAL_HOURS, settings.clampedIntervalHours)
            .apply()
        AppLog.i(
            "ConfigRepository",
            "Saved keeper enabled=${settings.enabled} " +
                "intervalHours=${settings.clampedIntervalHours}",
        )
    }

    /** True when DVR is configured and the relay should auto-start (e.g. after reboot). */
    fun isRelayConfigured(): Boolean {
        return hasWorkingConfig() && loadRelaySettings().enabled
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun hasWorkingConfig(): Boolean {
        val cfg = load() ?: return false
        return cfg.isComplete() && cfg.verified
    }

    companion object {
        private const val PREFS_NAME = "dvr_secure_prefs"
        private const val PREFS_FALLBACK = "dvr_prefs_fallback"
        private const val KEY_HOST = "host"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_RTSP_PORT = "rtsp_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_STREAM_TYPE = "stream_type"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_LISTEN_PORT = "listen_port"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
        private const val KEY_SEGMENTATION_ENABLED = "segmentation_enabled"
        private const val KEY_SEGMENTATION_INTERVAL_HOURS = "segmentation_interval_hours"
    }
}
