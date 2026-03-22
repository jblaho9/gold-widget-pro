package com.goldwidget.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        handleDeepLink(intent)
        refreshUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ── Deep link handler ─────────────────────────────────────────────────

    private fun handleDeepLink(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "com.goldwidget.pro" && data.host == "oauth") {
            val code = data.getQueryParameter("code") ?: run {
                showToast("Authorization failed — no code received")
                return
            }
            setStatus("Connecting…")
            Thread { exchangeCode(code) }.start()
        }
    }

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

        // Fetch account info and store account ID for the widget
        val accounts = CTraderApiService.getTradingAccounts(tokens.accessToken)
        val first = accounts?.firstOrNull()
        if (first != null) {
            TokenManager.saveAccountInfo(this, first.accountId, first.brokerName,
                first.balance, first.currency)
        }
        runOnUiThread { refreshUI() }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val connected = TokenManager.hasValidToken(this)

        findViewById<TextView>(R.id.tv_status).text =
            if (connected) "Connected" else "Not connected"

        val llInfo   = findViewById<LinearLayout>(R.id.ll_account_info)
        val btnConn  = findViewById<Button>(R.id.btn_connect)
        val btnDisc  = findViewById<Button>(R.id.btn_disconnect)

        if (connected) {
            llInfo.visibility  = View.VISIBLE
            btnConn.visibility = View.GONE
            btnDisc.visibility = View.VISIBLE

            val broker   = TokenManager.getBrokerName(this) ?: "Unknown broker"
            val balance  = TokenManager.getBalance(this)
            val currency = TokenManager.getCurrency(this)
            val accountId = TokenManager.getAccountId(this)
            findViewById<TextView>(R.id.tv_broker).text    = broker
            findViewById<TextView>(R.id.tv_account_id).text =
                if (accountId != null) "Account: $accountId" else "Account: —"
            findViewById<TextView>(R.id.tv_balance).text   =
                "Balance: ${"%.2f".format(balance)} $currency"
        } else {
            llInfo.visibility  = View.GONE
            btnConn.visibility = View.VISIBLE
            btnDisc.visibility = View.GONE
        }

        btnConn.setOnClickListener { openCTraderAuth() }
        btnDisc.setOnClickListener { disconnect() }
    }

    private fun setStatus(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.tv_status).text = text }
    }

    private fun openCTraderAuth() {
        val url = CTraderApiService.getAuthUrl()
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun disconnect() {
        TokenManager.clearAll(this)
        refreshUI()
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
