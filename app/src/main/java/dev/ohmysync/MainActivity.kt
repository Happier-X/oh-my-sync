package dev.ohmysync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.ohmysync.ui.SyncApp
import dev.ohmysync.auth.OneDriveAuth

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) accept(intent)
        setContent { SyncApp(model) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }
    private fun accept(intent: Intent) {
        intent.data?.takeIf { it.scheme == OneDriveAuth.SCHEME && it.host == "oauth" }?.let {
            model.callback(it)
            intent.data = null
        }
    }
}
