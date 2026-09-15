package org.bayton.gmc.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.SectionLabel
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton

private const val HPAD = 22

@Composable
fun AccountContent(contentPadding: PaddingValues, onSignedOut: () -> Unit, onOpenSettings: () -> Unit = {}, vm: AccountViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(contentPadding)
        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .verticalScroll(rememberScrollState()).padding(horizontal = HPAD.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundAvatar(ui.name, 60, c)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(ui.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("Gwent Music parent", color = c.inkFaint, fontSize = 12.5f.sp)
            }
        }
        org.bayton.gmc.ui.components.FreshnessPill(ui.freshness, ui.syncedAt, Modifier.padding(top = 12.dp), onRetry = vm::load)
        Spacer(Modifier.height(24.dp))

        SectionLabel("Contact", modifier = Modifier.padding(bottom = 4.dp))
        InfoRow(Icons.Outlined.Mail, "Email", ui.email.ifBlank { "-" }, c)
        ui.phone?.let { InfoRow(Icons.Outlined.Phone, "Phone", it, c) }
        if (ui.address.isNotEmpty()) InfoRow(Icons.Outlined.Place, "Address", ui.address.joinToString("\n"), c)
        ui.accountNo?.let { InfoRow(Icons.Outlined.Receipt, "Account number", it, c) }

        if (ui.pupils.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Pupils", modifier = Modifier.padding(bottom = 4.dp))
            ui.pupils.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundAvatar(p, 36, c)
                    Spacer(Modifier.width(12.dp))
                    Text(p, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                HorizontalDivider(color = c.line)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.canvas2)
            .clickable(interactionSource = null, indication = null, onClick = onOpenSettings).padding(horizontal = 14.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Settings, null, tint = c.inkDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(13.dp))
            Text("Settings", color = c.ink, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.inkMute, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { vm.signOut(); onSignedOut() },
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, c.line2),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.red),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Sign out", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun InfoRow(icon: ImageVector, label: String, value: String, c: BaytonColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = c.inkDim, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = c.inkFaint, fontSize = 11.5f.sp)
            Spacer(Modifier.height(1.dp))
            Text(value, color = c.ink, fontSize = 14.5f.sp, lineHeight = 20.sp)
        }
    }
    HorizontalDivider(color = c.line)
}

@Composable private fun RoundAvatar(name: String, size: Int, c: BaytonColors) = Box(
    Modifier.size(size.dp).clip(CircleShape).background(c.accentSoft).border(1.dp, c.accentLine, CircleShape),
    contentAlignment = Alignment.Center,
) {
    Text(name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
        color = c.accent2, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
}
