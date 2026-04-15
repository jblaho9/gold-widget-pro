package com.goldwidget.pro

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

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
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
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
     * Get all open positions via the cTrader Open API JSON protocol over raw SSL TCP
     * (live.ctraderapi.com:5036). Each message is a JSON object; the server responds
     * to each send with one JSON object (no length prefix, no WebSocket framing).
     */
    fun getPositions(accessToken: String, accountId: Long): List<TradeData>? {
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket  = factory.createSocket("live.ctraderapi.com", 5036)
            socket.soTimeout = 7000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // App auth → expect 2101
            tcpSendJson(out, """{"payloadType":2100,"payload":{"clientId":"$CLIENT_ID","clientSecret":"$CLIENT_SECRET"},"clientMsgId":"1"}""")
            val r1 = tcpRecvJson(inp)
            val pt1 = r1?.optInt("payloadType") ?: 0
            Log.d("CTrader", "AppAuth resp payloadType=$pt1")
            if (pt1 != 2101) {
                lastError = "AppAuth fail pt=$pt1: ${r1?.optJSONObject("payload")?.optString("description") ?: "no response"}"
                socket.close(); return null
            }

            // Account auth → expect 2103
            tcpSendJson(out, """{"payloadType":2102,"payload":{"ctidTraderAccountId":$accountId,"accessToken":"$accessToken"},"clientMsgId":"2"}""")
            val r2 = tcpRecvJson(inp)
            val pt2 = r2?.optInt("payloadType") ?: 0
            Log.d("CTrader", "AccountAuth resp payloadType=$pt2")
            if (pt2 != 2103) {
                lastError = "AccountAuth fail pt=$pt2: ${r2?.optJSONObject("payload")?.optString("description") ?: "no response"}"
                socket.close(); return null
            }

            // Reconcile → expect 2125
            tcpSendJson(out, """{"payloadType":2124,"payload":{"ctidTraderAccountId":$accountId},"clientMsgId":"3"}""")
            val r3 = tcpRecvJson(inp)
            val pt3 = r3?.optInt("payloadType") ?: 0
            Log.d("CTrader", "Reconcile resp payloadType=$pt3")
            if (pt3 != 2125) {
                lastError = "Reconcile fail pt=$pt3: ${r3?.optJSONObject("payload")?.optString("description") ?: "no response"}"
                socket.close(); return null
            }

            val positions = parseJsonPositions(r3!!.optJSONObject("payload"))
            Log.d("CTrader", "Reconcile: ${positions.size} positions")
            socket.close()
            positions
        } catch (e: Exception) {
            lastError = "TCP error: ${e.message}"
            Log.e("CTrader", "getPositions TCP failed", e)
            null
        }
    }

    private fun parseJsonPositions(payload: JSONObject?): List<TradeData> {
        if (payload == null) return emptyList()
        val arr = payload.optJSONArray("position") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val pos = arr.getJSONObject(i)
                val td  = pos.optJSONObject("tradeData") ?: return@mapNotNull null
                val sideRaw = td.optString("tradeSide", "BUY")
                // tradeSide can be string ("SELL"/"BUY") or numeric enum (BUY=1, SELL=2)
                val side = when {
                    sideRaw.contains("SELL", ignoreCase = true) -> "SELL"
                    sideRaw == "2" -> "SELL"
                    else -> "BUY"
                }
                TradeData(
                    positionId = pos.optString("positionId", ""),
                    symbol     = "XAUUSD",
                    side       = side,
                    volumeLots = td.optLong("volume", 0) / 10000.0,
                    entryPrice = pos.optDouble("price", 0.0),
                    swap       = pos.optDouble("swap", 0.0) / 100.0,
                    commission = pos.optDouble("commission", 0.0) / 100.0,
                    openTime   = td.optLong("openTimestamp", System.currentTimeMillis()),
                    stopLoss   = pos.optDouble("stopLoss", 0.0),
                    takeProfit = pos.optDouble("takeProfit", 0.0)
                )
            } catch (e: Exception) { null }
        }
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

    /** Public helper for diagnostics — returns (httpCode, body) or null on network error. */
    fun getRawPublic(url: String): Pair<Int, String>? {
        return try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: ""
            Pair(response.code, body)
        } catch (e: Exception) { null }
    }

    /** Diagnostic: send JSON app auth and show full server response. */
    fun diagWebSocket(): String {
        val sb = StringBuilder()
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sb.appendLine("connecting raw SSL TCP…")
            val socket = factory.createSocket("live.ctraderapi.com", 5036)
            socket.soTimeout = 8000
            sb.appendLine("connected")
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            val msg = """{"payloadType":2100,"payload":{"clientId":"$CLIENT_ID","clientSecret":"$CLIENT_SECRET"},"clientMsgId":"1"}"""
            sb.appendLine("sending JSON (${msg.length} chars)")
            tcpSendJson(out, msg)
            sb.appendLine("sent — waiting…")

            val resp = tcpRecvJson(inp)
            sb.appendLine("payloadType=${resp?.optInt("payloadType") ?: "null"}")
            sb.appendLine(resp?.toString(2) ?: "(no response)")
            socket.close()
            sb.toString()
        } catch (e: Exception) {
            sb.appendLine("error: ${e.javaClass.simpleName}: ${e.message}")
            sb.toString()
        }
    }

    // ── JSON over raw SSL TCP helpers ────────────────────────────────────

    private fun tcpSendJson(out: java.io.OutputStream, json: String) {
        out.write((json + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Read one complete JSON object from the stream by counting braces.
     * Handles the server not using any length prefix or newline terminator.
     */
    private fun tcpRecvJson(inp: java.io.InputStream): JSONObject? {
        val sb  = StringBuilder()
        val buf = ByteArray(4096)
        var depth = 0; var inStr = false; var esc = false; var started = false
        try {
            outer@ while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    sb.append(c)
                    if (esc)         { esc = false; continue }
                    if (c == '\\' && inStr) { esc = true;  continue }
                    if (c == '"')    { inStr = !inStr; continue }
                    if (!inStr) {
                        if (c == '{') { depth++; started = true }
                        else if (c == '}') { depth--; if (started && depth == 0) break@outer }
                    }
                }
            }
        } catch (_: java.net.SocketTimeoutException) { /* return whatever we have */ }
        if (sb.isEmpty()) return null
        return try { JSONObject(sb.toString()) } catch (e: Exception) { null }
    }

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
