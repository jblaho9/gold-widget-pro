package com.goldwidget.pro

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var wvOAuth: WebView
    private lateinit var llOAuth: LinearLayout
    private lateinit var svMain: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        svMain  = findViewById(R.id.sv_main)
        llOAuth = findViewById(R.id.ll_oauth)
        wvOAuth = findViewById(R.id.wv_oauth)

        setupWebView()
        refreshUI()
    }

    override fun onBackPressed() {
        // If OAuth WebView is visible and can go back, navigate back within it
        if (llOAuth.visibility == View.VISIBLE) {
            if (wvOAuth.canGoBack()) {
                wvOAuth.goBack()
            } else {
                cancelOAuth()
            }
            return
        }
        super.onBackPressed()
    }

    // ── WebView setup ─────────────────────────────────────────────────────

    private fun setupWebView() {
        wvOAuth.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        wvOAuth.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Intercept our redirect URI before the WebView tries to load it
                if (url.startsWith(CTraderApiService.REDIRECT_URI)) {
                    val uri   = Uri.parse(url)
                    val code  = uri.getQueryParameter("code")
                    val error = uri.getQueryParameter("error")
                    cancelOAuth()   // hide WebView first
                    if (code != null) {
                        setStatus("Connecting…")
                        Thread { exchangeCode(code) }.start()
                    } else {
                        showToast("Authorization failed: ${error ?: "cancelled"}")
                        refreshUI()
                    }
                    return true
                }
                return false
            }
        }

        findViewById<Button>(R.id.btn_cancel_oauth).setOnClickListener { cancelOAuth() }
    }

    private fun showOAuth() {
        svMain.visibility  = View.GONE
        llOAuth.visibility = View.VISIBLE
        wvOAuth.loadUrl(CTraderApiService.getAuthUrl())
    }

    private fun cancelOAuth() {
        llOAuth.visibility = View.GONE
        svMain.visibility  = View.VISIBLE
        wvOAuth.stopLoading()
    }

    // ── Token exchange ────────────────────────────────────────────────────

    private fun exchangeCode(code: String) {
        val tokens = CTraderApiService.exchangeCode(code)
        if (tokens == null) {
            runOnUiThread {
                showToast("Connection failed. Please try again.")
                refreshUI()
            }
            return
        }
        TokenManager.saveTokens(this, tokens.accessToken, tokens.refreshToken, tokens.expiresIn)

        // Fetch and save the first trading account so the widget knows which account to poll
        val accounts = CTraderApiService.getTradingAccounts(tokens.accessToken)
        val first = accounts?.firstOrNull()
        if (first != null) {
            TokenManager.saveAccountInfo(this, first.accountId, first.brokerName,
                first.balance, first.currency)
        } else {
            val msg = if (accounts == null) CTraderApiService.lastError
                      else "No trading accounts found for this token"
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("API Error")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        runOnUiThread { refreshUI() }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val connected = TokenManager.hasValidToken(this)

        findViewById<TextView>(R.id.tv_status).text =
            if (connected) "Connected" else "Not connected"

        val llInfo  = findViewById<LinearLayout>(R.id.ll_account_info)
        val btnConn = findViewById<Button>(R.id.btn_connect)
        val btnDisc = findViewById<Button>(R.id.btn_disconnect)

        if (connected) {
            llInfo.visibility  = View.VISIBLE
            btnConn.visibility = View.GONE
            btnDisc.visibility = View.VISIBLE

            val broker    = TokenManager.getBrokerName(this) ?: "Unknown broker"
            val balance   = TokenManager.getBalance(this)
            val currency  = TokenManager.getCurrency(this)
            val accountId = TokenManager.getAccountId(this)
            findViewById<TextView>(R.id.tv_broker).text     = broker
            findViewById<TextView>(R.id.tv_account_id).text =
                if (accountId != null) "Account: $accountId" else "Account: —"
            findViewById<TextView>(R.id.tv_balance).text    =
                "Balance: ${"%.2f".format(balance)} $currency"
        } else {
            llInfo.visibility  = View.GONE
            btnConn.visibility = View.VISIBLE
            btnDisc.visibility = View.GONE
        }

        btnConn.setOnClickListener { showOAuth() }
        btnDisc.setOnClickListener { disconnect() }

        val btnTest  = findViewById<Button>(R.id.btn_test_trades)
        val tvDebug  = findViewById<TextView>(R.id.tv_trade_debug)
        if (connected) {
            btnTest.visibility  = View.VISIBLE
            tvDebug.visibility  = View.VISIBLE
            btnTest.setOnClickListener {
                tvDebug.text = "Fetching…"
                Thread {
                    val token     = CTraderApiService.getValidToken(this)
                    val accountId = TokenManager.getAccountId(this)
                    val sb = StringBuilder()
                    sb.appendLine("token: ${token?.take(12) ?: "NULL"}")
                    sb.appendLine("accountId: $accountId")
                    // Step 1: verify app auth works
                    val diag = CTraderApiService.diagWebSocket()
                    sb.appendLine("--- app auth diag ---")
                    sb.appendLine(diag)
                    // Step 2: full positions fetch
                    if (token != null && accountId != null) {
                        sb.appendLine("--- positions ---")
                        val positions = CTraderApiService.getPositions(token, accountId)
                        sb.appendLine("count: ${positions?.size ?: "NULL"}")
                        sb.appendLine("lastError: ${CTraderApiService.lastError.ifEmpty { "none" }}")
                        positions?.forEach { sb.appendLine("  ${it.side} ${it.symbol} ${it.volumeLots}L @ ${it.entryPrice}") }
                    }
                    runOnUiThread { tvDebug.text = sb.toString() }
                }.start()
            }
        } else {
            btnTest.visibility = View.GONE
            tvDebug.visibility = View.GONE
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.tv_status).text = text }
    }

    private fun disconnect() {
        TokenManager.clearAll(this)
        refreshUI()
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
