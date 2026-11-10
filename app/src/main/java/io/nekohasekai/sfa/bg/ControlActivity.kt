package io.nekohasekai.sfa.bg

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Headless entry point for other apps (launchers, Tasker, adb) to start, stop or
 * toggle the proxy without opening the main UI. See docs/external-control.md.
 *
 * The VPN consent dialog is shown only if Android has not granted it yet.
 */
class ControlActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ControlActivity"
        const val ACTION_START = "io.github.liuw.surgebridge.action.START"
        const val ACTION_STOP = "io.github.liuw.surgebridge.action.STOP"
        const val ACTION_TOGGLE = "io.github.liuw.surgebridge.action.TOGGLE"
        private const val STATUS_TIMEOUT_MS = 2_000L
        private const val KEY_AWAITING_CONSENT = "awaiting_consent"
    }

    private var awaitingConsent = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) BoxService.start()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while the consent dialog is open: its result arrives via vpnPermission.
        if (savedInstanceState?.getBoolean(KEY_AWAITING_CONSENT) == true) {
            awaitingConsent = true
            return
        }
        lifecycleScope.launch {
            when (intent?.action) {
                ACTION_START -> start()
                ACTION_STOP -> stop()
                ACTION_TOGGLE -> if (currentStatus() == Status.Stopped) start() else stop()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING_CONSENT, awaitingConsent)
    }

    private suspend fun start() {
        Settings.dataStore.initialize()
        withContext(Dispatchers.IO) { Settings.rebuildServiceMode() }
        if (Settings.serviceMode == ServiceMode.VPN) {
            val prepare = try {
                VpnService.prepare(this)
            } catch (e: Exception) {
                Log.w(TAG, "VPN prepare failed", e)
                openMainUi()
                return
            }
            if (prepare != null) {
                awaitingConsent = true
                vpnPermission.launch(prepare)
                return
            }
        }
        BoxService.start()
        finish()
    }

    private fun stop() {
        BoxService.stop()
        finish()
    }

    private suspend fun currentStatus(): Status {
        val result = CompletableDeferred<Status>()
        val connection = ServiceConnection(
            this,
            object : ServiceConnection.Callback {
                override fun onServiceStatusChanged(status: Status) {
                    result.complete(status)
                }
            },
            register = false,
        )
        connection.connect()
        return try {
            withTimeoutOrNull(STATUS_TIMEOUT_MS) { result.await() } ?: Status.Stopped
        } finally {
            connection.disconnect()
        }
    }

    private fun openMainUi() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
