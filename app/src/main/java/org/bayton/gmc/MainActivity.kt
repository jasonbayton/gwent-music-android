package org.bayton.gmc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.auth.TokenStore
import org.bayton.gmc.notify.NotificationIntentState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.bayton.gmc.ui.GmcApp
import org.bayton.gmc.ui.components.baytonBackground
import org.bayton.gmc.ui.theme.GwentMusicTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: Session
    @Inject lateinit var tokens: TokenStore
    private val notificationIntents = NotificationIntentState()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        if (tokens.accessToken.isNullOrBlank()) session.clear()
        notificationIntents.restore(savedInstanceState, intent)
        setContent {
            val signedIn by session.signedIn.collectAsStateWithLifecycle()
            val notificationRequest by notificationIntents.pending.collectAsStateWithLifecycle()
            GwentMusicTheme {
                // One viewport-anchored background layer (canvas + DS ambient glow) for the whole app,
                // outside key() so it persists across the auth remount and flips atomically on theme change.
                Box(Modifier.fillMaxSize().baytonBackground()) {
                    // Drop the account back stack and its ViewModels on terminal auth failure.
                    key(signedIn) {
                        GmcApp(startSignedIn = signedIn, notificationRequest = notificationRequest,
                            onNotificationConsumed = notificationIntents::consume)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationIntents.accept(intent)
    }

    override fun onStart() {
        super.onStart()
        org.bayton.gmc.update.UpdateChecks.onAppOpen(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        notificationIntents.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
