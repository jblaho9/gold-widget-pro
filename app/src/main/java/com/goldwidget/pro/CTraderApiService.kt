package com.goldwidget.pro

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles cTrader Open API v1 (REST + OAuth2).
 *
 * SETUP — before building:
 *  1. Go to https://connect.spotware.com and register a new app.
 *  2. Set the redirect URI to exactly: http://localhost/callback
 *  3. Copy your Client ID and Client Secret into the constants below.
 */
object CTraderApiService {

    // Credentials are injected at build time from local.properties (gitignored)
    private val CLIENT_ID     get() = BuildConfig.CTRADER_CLIENT_ID
    private val CLIENT_SECRET get() = BuildConfig.CTRADER_CLIENT_SECRET
    const val REDIRECT_URI          = "http://localhost/callback"

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
            Log.d("CTrader", "token prefix: ${accessToken.take(20)}")
            val url = "$API_BASE/tradingaccounts?access_token=${Uri.encode(accessToken)}"
            val raw = getRaw(url)
            Log.d("CTrader", "tradingaccounts raw: $raw")
            if (raw == null) return null
            val json = JSONObject(raw)
            val arr = json.getJSONArray("data")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Log.d("CTrader", "account obj: $obj")
                TradingAccount(
                    accountId  = obj.optLong("accountId", obj.optLong("ctidTraderAccountId", 0)),
                    brokerName = obj.optString("brokerName", obj.optString("broker", "")),
                    balance    = obj.optDouble("balance", 0.0),
                    currency   = obj.optString("depositCurrency", obj.optString("currency", "USD"))
                )
            }
        } catch (e: Exception) {
            Log.e("CTrader", "getTradingAccounts failed", e)
            null
        }
    }

    /**
     * Get all open positions via the cTrader WebSocket JSON API (live.ctraderapi.com:5036).
     * REST has no positions endpoint — positions require WebSocket + 3-step auth handshake.
     */
    fun getPositions(accessToken: String, accountId: Long): List<TradeData>? {
        val latch = CountDownLatch(1)
        var result: List<TradeData>? = null
        var spotSymbolId = 0L

        val wsClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://live.ctraderapi.com:5036")
            .build()

        wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("CTrader", "WS open — sending app auth")
                ws.send("""{"payloadType":2100,"clientMsgId":"1","payload":{"clientId":"$CLIENT_ID","clientSecret":"$CLIENT_SECRET"}}""")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("CTrader", "WS msg: $text")
                try {
                    val msg = JSONObject(text)
                    when (msg.optInt("payloadType")) {
                        2101 -> { // ApplicationAuthRes → send account auth
                            ws.send("""{"payloadType":2102,"clientMsgId":"2","payload":{"accessToken":"$accessToken","ctidTraderAccountId":$accountId}}""")
                        }
                        2103 -> { // AccountAuthRes → request positions
                            ws.send("""{"payloadType":2124,"clientMsgId":"3","payload":{"ctidTraderAccountId":$accountId}}""")
                        }
                        2125 -> { // ReconcileRes → parse positions
                            val payload = msg.optJSONObject("payload")
                            val arr = payload?.optJSONArray("position") ?: JSONArray()
                            result = (0 until arr.length()).mapNotNull { i ->
                                try {
                                    val pos = arr.getJSONObject(i)
                                    val td  = pos.getJSONObject("tradeData")
                                    val sideRaw = td.optString("tradeSide", "BUY")
                                    val side = if (sideRaw == "2" || sideRaw.equals("SELL", ignoreCase = true)) "SELL" else "BUY"
                                    if (spotSymbolId == 0L) spotSymbolId = td.optLong("symbolId", 0L)
                                    TradeData(
                                        positionId = pos.optLong("positionId", 0).toString(),
                                        symbol     = "XAUUSD",
                                        side       = side,
                                        volumeLots = td.optLong("volume", 0L) / 100.0,
                                        entryPrice = pos.optDouble("price", 0.0),
                                        swap       = pos.optDouble("swap", 0.0) / 100.0,
                                        commission = pos.optDouble("commission", 0.0) / 100.0,
                                        openTime   = td.optLong("openTimestamp", System.currentTimeMillis()),
                                        stopLoss   = pos.optDouble("stopLoss", 0.0),
                                        takeProfit = pos.optDouble("takeProfit", 0.0)
                                    )
                                } catch (e: Exception) {
                                    Log.e("CTrader", "position parse error", e); null
                                }
                            }
                            if (spotSymbolId > 0L) {
                                // Subscribe to spot to get cTrader's live bid price
                                ws.send("""{"payloadType":2126,"clientMsgId":"4","payload":{"ctidTraderAccountId":$accountId,"symbolId":[$spotSymbolId]}}""")
                            } else {
                                ws.close(1000, "done")
                                latch.countDown()
                            }
                        }
                        2131 -> { // ProtoOASpotEvent → capture bid, then unsubscribe
                            val payload = msg.optJSONObject("payload")
                            val rawBid = payload?.optDouble("bid", 0.0) ?: 0.0
                            // cTrader may return bid as integer (e.g. 320345 = $3203.45) or as double
                            lastLiveBid = if (rawBid > 10000) rawBid / 100.0 else rawBid
                            Log.d("CTrader", "spot bid raw=$rawBid lastLiveBid=$lastLiveBid")
                            ws.send("""{"payloadType":2128,"clientMsgId":"5","payload":{"ctidTraderAccountId":$accountId,"symbolId":[$spotSymbolId]}}""")
                            ws.close(1000, "done")
                            latch.countDown()
                        }
                        50 -> { // ProtoOAError
                            val err = msg.optJSONObject("payload")?.optString("description", "unknown") ?: "unknown"
                            lastError = "WS err: $err"
                            Log.e("CTrader", "WS API error: $text")
                            ws.close(1000, "error")
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CTrader", "WS parse error", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                lastError = "WS fail: ${t.message}"
                Log.e("CTrader", "WS failure", t)
                latch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        if (!latch.await(30, TimeUnit.SECONDS)) {
            lastError = "WS timeout"
            Log.e("CTrader", "WS timed out")
            return null
        }
        return result
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

    var lastError: String = ""
    var lastLiveBid: Double = 0.0

    private fun getRaw(url: String, token: String? = null): String? {
        return try {
            val req = Request.Builder().url(url)
            if (token != null) req.header("Authorization", "Bearer $token")
            val response = client.newCall(req.build()).execute()
            val body = response.body?.string() ?: ""
            Log.d("CTrader", "GET $url → HTTP ${response.code} body=$body")
            if (!response.isSuccessful) {
                lastError = "HTTP ${response.code}: ${body.take(300)}"
                null
            } else body
        } catch (e: Exception) {
            lastError = "Network error: ${e.message}"
            Log.e("CTrader", "getRaw failed for $url", e)
            null
        }
    }

    private fun get(url: String, token: String? = null): JSONObject? {
        val raw = getRaw(url, token) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }
}
