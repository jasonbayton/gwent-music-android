package org.bayton.gmc.ui.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton

@Composable
fun BillingScreen(onBack: () -> Unit, vm: BillingViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    GmcScaffold(title = "Billing", onBack = onBack) { pad ->
        StateBox(ui.loading, ui.error, ui.isEmpty, "No billing information", pad, retry = vm::load) {
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(6.dp))
                // Balance hero: a glowing band, echoing Home's "next up".
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(c.accentSoft, Color.Transparent)))
                    .border(1.dp, c.accentLine, RoundedCornerShape(18.dp)).padding(20.dp)) {
                    Text("Outstanding balance", color = c.inkDim, fontSize = 12.5f.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(money(ui.balance), color = c.ink, style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp))
                    val w = ui.wallet
                    if (w?.nextInstalment != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, tint = c.accent2, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Next instalment ${money(w.nextInstalment)}${w.dueDate?.let { " · due " + fmtDate(it) } ?: ""}",
                                color = c.inkFaint, fontSize = 13.sp)
                        }
                    }
                    // scheduledPayments is a distinct total - show it whenever present, not only when
                    // there is no next instalment.
                    ui.debtor?.scheduledPayments?.takeIf { it > 0 }?.let {
                        Spacer(Modifier.height(4.dp)); Text("${money(it)} scheduled", color = c.inkFaint, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val w = ui.wallet
                if (!w?.cardType.isNullOrBlank()) InfoRow(Icons.Outlined.CreditCard, "Card on file",
                    listOfNotNull(
                        w?.cardType?.replaceFirstChar { it.uppercase() },
                        w?.last4Digits?.let { "•••• $it" },
                        w?.expires?.let { e -> "exp " + if (e.length == 4) "${e.take(2)}/${e.drop(2)}" else e },
                    ).joinToString("  "), c)
                ui.debtor?.accountNo?.let { InfoRow(Icons.Outlined.Receipt, "Account", it, c) }
                w?.let { if (it.transValue != null) InfoRow(Icons.Outlined.Event, "Last payment",
                    "${money(it.paidValue ?: it.transValue)}${it.transDate?.let { d -> " · " + fmtDate(d) } ?: ""}", c) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, c: BaytonColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.line), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.inkDim, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = c.inkFaint, fontSize = 12.sp)
            Spacer(Modifier.height(1.dp))
            Text(value, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5f.sp)
        }
    }
    HorizontalDivider(color = c.line)
}
