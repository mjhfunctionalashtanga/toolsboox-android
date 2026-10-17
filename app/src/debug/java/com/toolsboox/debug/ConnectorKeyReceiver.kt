package com.toolsboox.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.toolsboox.plugin.michaelfilter.nw.MichaelFilterConnector

/**
 * DEBUG BUILDS ONLY — set the MichaelFilter key over adb (see src/debug/AndroidManifest.xml).
 *
 *   adb shell am broadcast -a com.toolsboox.mjh.debug.SET_CONNECTOR_KEY \
 *       -n com.toolsboox.mjh.debug/com.toolsboox.debug.ConnectorKeyReceiver --es mf_key '<KEY>'
 *
 * The key goes straight into [MichaelFilterConnector.setKey] — the same door the Settings box uses,
 * so the connected flag, the "turned down" reset and the queue's re-send all follow. Nothing here
 * writes the key anywhere else: the log line and the broadcast's result carry only its fingerprint
 * (four hex characters of its SHA-256), and nothing is shown on screen. An empty `mf_key` removes
 * the key; a missing one changes nothing.
 */
class ConnectorKeyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.toolsboox.mjh.debug.SET_CONNECTOR_KEY"
        const val EXTRA_MF_KEY = "mf_key"
        private const val TAG = "ConnectorKey"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!intent.hasExtra(EXTRA_MF_KEY)) {
            answer("No mf_key extra — nothing changed")
            return
        }
        val raw = intent.getStringExtra(EXTRA_MF_KEY)
        val app = context.applicationContext
        // The encrypted store can take a second or two to open on e-ink hardware; keep it off the
        // main thread and hold the broadcast open until the write has landed.
        val pending = goAsync()
        Thread {
            val line = try {
                val fp = MichaelFilterConnector.setKey(app, raw)
                when {
                    fp == null -> "MichaelFilter key not set (key store unavailable)"
                    MichaelFilterConnector.normalize(raw).isEmpty() -> "MichaelFilter key removed"
                    else -> "MichaelFilter key set (fingerprint $fp)"
                }
            } catch (e: Exception) {
                "MichaelFilter key not set (${e.javaClass.simpleName})"
            }
            Log.i(TAG, line)
            pending.resultData = line
            pending.finish()
        }.start()
    }

    private fun answer(line: String) {
        Log.i(TAG, line)
        resultData = line
    }
}
