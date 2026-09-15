package org.bayton.gmc.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.BaytonCard
import org.bayton.gmc.ui.components.GmcScaffold
import org.bayton.gmc.ui.components.baytonBackground
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.bayton.gmc.ui.theme.LocalBayton

/** Standalone, non-exported screen: removing the updater requires no navigation graph edits. */
class UpdateActivity : ComponentActivity() {
    private val vm: UpdateViewModel by viewModels()
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Settings does not reliably return RESULT_OK. Check the actual permission on return.
        vm.installPermissionReturned(packageManager.canRequestPackageInstalls(), ::launchInstaller)
    }

    private fun launchInstaller(apk: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GwentMusicTheme {
                Box(Modifier.fillMaxSize().baytonBackground()) {
                val state by vm.state.collectAsStateWithLifecycle()
                var automatic by remember { mutableStateOf(UpdateChecks.enabled(this@UpdateActivity)) }
                GmcScaffold(title = "App updates", onBack = { finish() }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BaytonCard {
                            Text("Installed version ${vm.installed.versionName} (${vm.installed.versionCode})")
                            Text("Updates are downloaded from cdn.bayton.org. Nothing is installed without your confirmation.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatic checks")
                                Text("Check on app open, at most once a day. Update notifications are silent by default.",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = automatic, onCheckedChange = {
                                automatic = it
                                UpdateChecks.setEnabled(this@UpdateActivity, it)
                            })
                        }
                        state.release?.takeIf { state.phase != UpdatePhase.CURRENT }?.let { release ->
                            Text("Version ${release.versionName} is available", style = MaterialTheme.typography.titleLarge)
                            if (release.releaseNotes.isNotBlank()) Text(release.releaseNotes)
                        }
                        state.message?.let { Text(it) }
                        when (state.phase) {
                            UpdatePhase.IDLE, UpdatePhase.ERROR, UpdatePhase.CURRENT -> {
                                if (state.phase == UpdatePhase.CURRENT) Text("You're up to date.")
                                Button(onClick = vm::check) { Text("Check for updates") }
                            }
                            UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (state.phase == UpdatePhase.CHECKING) "Checking for updates..." else "Verifying APK...")
                            }
                            UpdatePhase.AVAILABLE -> {
                                Button(onClick = vm::download) { Text("Download update") }
                                TextButton(onClick = { finish() }) { Text("Not now") }
                            }
                            UpdatePhase.DOWNLOADING -> {
                                state.progress?.let { progress ->
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    Text("Downloading: ${(progress * 100).toInt()}%")
                                } ?: run {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("Downloading update...")
                                }
                                TextButton(onClick = vm::cancel) { Text("Cancel download") }
                            }
                            UpdatePhase.READY -> Button(onClick = {
                                vm.prepareInstall { apk ->
                                    if (!packageManager.canRequestPackageInstalls()) {
                                        vm.installPermissionNeeded()
                                        installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                                    } else {
                                        launchInstaller(apk)
                                    }
                                }
                            }) { Text("Install update") }
                        }
                    }
                }
                }
            }
        }
        if (savedInstanceState == null) vm.check()
    }
}

/** The single Settings integration point. */
@Composable
fun UpdateSettingsEntry(context: Context) {
    val c = LocalBayton.current
    OutlinedButton(
        onClick = { context.startActivity(Intent(context, UpdateActivity::class.java)) },
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, c.line2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Text("Check for updates", fontWeight = FontWeight.SemiBold)
    }
}
