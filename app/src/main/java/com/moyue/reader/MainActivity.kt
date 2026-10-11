package com.moyue.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.moyue.reader.feature.importbook.ShareReceiverViewModel

class MainActivity : ComponentActivity() {
    private val shareReceiver by lazy {
        val container = (application as MoyueApplication).container
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                requireNotNull(modelClass.cast(ShareReceiverViewModel(container)))
        }
        ViewModelProvider(this, factory)[ShareReceiverViewModel::class.java]
    }

    override fun onStart() {
        super.onStart()
        (application as MoyueApplication).container.updates.foreground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MoyueApplication).container
        // A recreated Activity must not import the original share a second time.
        if (savedInstanceState == null) shareReceiver.receive(intent)
        setContent { MoyueApp(container, shareReceiver = shareReceiver) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareReceiver.receive(intent)
    }
}
