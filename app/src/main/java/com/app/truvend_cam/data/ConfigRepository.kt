package com.app.truvend_cam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
    }
}
