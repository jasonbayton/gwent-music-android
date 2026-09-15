package org.bayton.gmc.ui.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import org.bayton.gmc.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.theme.LocalBayton

@Composable
fun LoginScreen(onSignedIn: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.done) { if (state.done) onSignedIn() }

    Box(Modifier.fillMaxSize()) {
        // Background + ambient glow come from the app-root baytonBackground().
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.gmc_logo), null, tint = c.accent, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text("Gwent Music", color = c.ink, style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Lessons, messages and schedules for your family - in one place.",
                color = c.inkFaint, textAlign = TextAlign.Center, fontSize = 14.5f.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(40.dp))

            Button(
                onClick = { vm.startLogin { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) } },
                enabled = !state.loading,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color(0xFF0E0D0C)),
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF0E0D0C), strokeWidth = 2.dp)
                else Text("Sign in with Gwent Music", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            if (vm.mockAvailable) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.mockSignIn() },
                    enabled = !state.loading,
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, c.line2),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.inkDim),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Use seeded mock data (debug)", fontWeight = FontWeight.SemiBold) }
            }

            state.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = c.red, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Uses your existing Gwent Music sign-in. Your password is entered only in your browser.",
                color = c.inkMute, textAlign = TextAlign.Center, fontSize = 11.5f.sp, lineHeight = 17.sp,
            )
        }
    }
}
