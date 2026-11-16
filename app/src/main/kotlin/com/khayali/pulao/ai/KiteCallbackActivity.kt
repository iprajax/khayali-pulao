package com.khayali.pulao.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives khayalipulao://kite/callback?code&state from the OAuth page and finishes the Kite login. */
class KiteCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val kite = KiteMcpClient.get(this)
        CoroutineScope(Dispatchers.Main).launch {
            kite.complete(uri?.getQueryParameter("code"), uri?.getQueryParameter("state"),
                uri?.getQueryParameter("error_description") ?: uri?.getQueryParameter("error"))
        }
        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
