package com.goldwidget.pro

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores OAuth2 tokens in EncryptedSharedPreferences so they are never
 * stored in plain text on the device. Falls back to regular prefs if
 * encryption is unavailable (e.g. in emulators without hardware keystore).
 */
object TokenManager {

    private const val PREFS_FILE    = "ctrader_tokens"
    private const val KEY_ACCESS    = "access_token"
    private const val KEY_REFRESH   = "refresh_token"
    private const val KEY_EXPIRES   = "expires_at"
    private const val KEY_ACCOUNT   = "account_id"
    private const val KEY_BROKER    = "broker_name"
    private const val KEY_BALANCE   = "account_balance"
    private const val KEY_CURRENCY  = "account_currency"

    private fun prefs(ctx: Context) = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun saveTokens(ctx: Context, accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn.toLong() * 1000L)
        prefs(ctx).edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, expiresAt)
            .apply()
    }

    fun saveAccountInfo(ctx: Context, accountId: Long, brokerName: String,
                        balance: Double, currency: String) {
        prefs(ctx).edit()
            .putLong(KEY_ACCOUNT, accountId)
            .putString(KEY_BROKER, brokerName)
            .putFloat(KEY_BALANCE, balance.toFloat())
            .putString(KEY_CURRENCY, currency)
            .apply()
    }

    fun getAccessToken(ctx: Context): String?  = prefs(ctx).getString(KEY_ACCESS, null)
    fun getRefreshToken(ctx: Context): String? = prefs(ctx).getString(KEY_REFRESH, null)
    fun getAccountId(ctx: Context): Long?      = prefs(ctx).getLong(KEY_ACCOUNT, -1L).takeIf { it != -1L }
    fun getBrokerName(ctx: Context): String?   = prefs(ctx).getString(KEY_BROKER, null)
    fun getBalance(ctx: Context): Double       = prefs(ctx).getFloat(KEY_BALANCE, 0f).toDouble()
    fun getCurrency(ctx: Context): String      = prefs(ctx).getString(KEY_CURRENCY, "USD") ?: "USD"

    fun isTokenExpired(ctx: Context): Boolean {
        val expiresAt = prefs(ctx).getLong(KEY_EXPIRES, 0L)
        return System.currentTimeMillis() >= expiresAt - 60_000L // 1 min buffer
    }

    fun hasValidToken(ctx: Context): Boolean {
        val token = getAccessToken(ctx) ?: return false
        return token.isNotEmpty()
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
