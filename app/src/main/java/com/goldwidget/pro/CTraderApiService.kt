package com.goldwidget.pro

import android.content.Context
import android.net.Uri
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles cTrader Open API v1 (REST + OAuth2).
 *
 * SETUP — before building:
 *  1. Go to https://connect.spotware.com and register a new app.
 *  2. Set the redirect URI to exactly: com.goldwidget.pro://oauth
 *  3. Copy your Client ID and Client Secret into the constants below.
 */
object CTraderApiService {

    // ─── Your app credentials from https://connect.spotware.com ─────────
    private const val CLIENT_ID     = "TODO_YOUR_CLIENT_ID"
    private const val CLIENT_SECRET = "TODO_YOUR_CLIENT_SECRET"
    const val REDIRECT_URI          = "http://localhost/callback"
    // ─────────────────────────────────────────────────────────────────────

    private const val AUTH_URL  = "https://connect.spotware.com/apps/auth"
    private const val TOKEN_URL = "https://connect.spotware.com/apps/token"
    private const val API_BASE  = "https://api.spotware.com/connect"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Data classes ──────────────────────────────────────────────────────

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int
    )

    data class TradingAccount(
        val accountId: Long,
        val brokerName: String,
        val balance: Double,
        val currency: String
    )

    // ── OAuth helpers ─────────────────────────────────────────────────────

    /** Build the URL to open in a browser for user login + consent. */
    fun getAuthUrl(): String =
        "$AUTH_URL?client_id=$CLIENT_ID" +
        "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
        "&response_type=code" +
        "&scope=trading"

    /** Exchange authorization code → access + refresh tokens (blocking). */
    fun exchangeCode(code: String): TokenResponse? {
        return try {
            val body = FormBody.Builder()
                .add("grant_type",    "authorization_code")
                .add("code",          code)
                .add("redirect_uri",  REDIRECT_URI)
                .add("client_id",     CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val responseBody = client.newCall(
                Request.Builder().url(TOKEN_URL).post(body).build()
            ).execute().body?.string() ?: return null
            parseTokenResponse(responseBody)
        } catch (e: Exception) { null }
    }

    /** Silently refresh an expired access token (blocking). */
    fun refreshAccessToken(refreshToken: String): TokenResponse? {
        return try {
            val body = FormBody.Builder()
                .add("grant_type",    "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id",     CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val responseBody = client.newCall(
                Request.Builder().url(TOKEN_URL).post(body).build()
            ).execute().body?.string() ?: return null
            parseTokenResponse(responseBody, fallbackRefresh = refreshToken)
        } catch (e: Exception) { null }
    }

    private fun parseTokenResponse(json: String, fallbackRefresh: String = ""): TokenResponse? = try {
        val j = JSONObject(json)
        TokenResponse(
            accessToken  = j.getString("access_token"),
            refreshToken = j.optString("refresh_token", fallbackRefresh),
            expiresIn    = j.getInt("expires_in")
        )
    } catch (e: Exception) { null }

    // ── REST API calls ────────────────────────────────────────────────────

    /** List all trading accounts for the authenticated user. */
    fun getTradingAccounts(accessToken: String): List<TradingAccount>? {
        return try {
            val json = get("$API_BASE/tradingaccounts?token=$accessToken") ?: return null
            val arr = json.getJSONArray("data")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TradingAccount(
                    accountId  = obj.getLong("accountId"),
                    brokerName = obj.optString("brokerName", ""),
                    balance    = obj.optDouble("balance", 0.0),
                    currency   = obj.optString("depositCurrency", "USD")
                )
            }
        } catch (e: Exception) { null }
    }

    /** Get all open positions for a trading account. */
    fun getPositions(accessToken: String, accountId: Long): List<TradeData>? {
        return try {
            val json = get("$API_BASE/tradingaccounts/$accountId/positions?token=$accessToken")
                ?: return null
            val arr = json.getJSONArray("position")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TradeData(
                    positionId = obj.getString("positionId"),
                    symbol     = obj.getString("symbolName"),
                    side       = obj.getString("tradeSide"),       // "BUY" or "SELL"
                    volumeLots = obj.getLong("volume") / 100.0,    // API unit: 100 = 1 lot
                    entryPrice = obj.getDouble("price"),
                    swap       = obj.optDouble("swap", 0.0),
                    commission = obj.optDouble("commission", 0.0),
                    openTime   = obj.optLong("openTimestamp", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) { null }
    }

    /**
     * Returns a valid (possibly refreshed) access token, or null if the user
     * is not connected. Call this from background threads only.
     */
    fun getValidToken(ctx: Context): String? {
        if (!TokenManager.hasValidToken(ctx)) return null
        if (TokenManager.isTokenExpired(ctx)) {
            val rt = TokenManager.getRefreshToken(ctx) ?: return null
            val newTokens = refreshAccessToken(rt) ?: return null
            TokenManager.saveTokens(ctx, newTokens.accessToken, newTokens.refreshToken, newTokens.expiresIn)
            return newTokens.accessToken
        }
        return TokenManager.getAccessToken(ctx)
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun get(url: String): JSONObject? {
        return try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JSONObject(body)
        } catch (e: Exception) { null }
    }
}
