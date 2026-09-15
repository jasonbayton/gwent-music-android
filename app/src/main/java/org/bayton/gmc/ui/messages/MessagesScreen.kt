package org.bayton.gmc.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.data.model.SecureMessage
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton

private const val HPAD = 22

@Composable
fun MessagesContent(
    contentPadding: PaddingValues,
    openMessage: (String) -> Unit,
    onCompose: () -> Unit,
    vm: MessagesViewModel = hiltViewModel(),
) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(contentPadding).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().padding(start = HPAD.dp, end = HPAD.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", color = c.ink, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
                    .clickable(interactionSource = null, indication = null, onClick = onCompose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Edit, "New message", tint = Color(0xFF0E0D0C), modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Segmented(listOf("Received", "Sent"), if (ui.sent) 1 else 0, c) { vm.select(it == 1) }
        FreshnessPill(ui.freshness, ui.syncedAt, Modifier.padding(horizontal = HPAD.dp).padding(top = 6.dp), onRetry = { vm.load(ui.sent) })
        StateBox(ui.loading, ui.error, ui.items.isEmpty(), "No messages", PaddingValues(), retry = { vm.load(ui.sent) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)) {
                itemsIndexed(ui.items, key = { i, m -> m.contactActivityItem?.let { "it:$it" } ?: "ix:$i" }) { _, m ->
                    val box = if (ui.sent) "sent" else "recv"
                    MessageRow(m, ui.sent, c) { m.contactActivityItem?.let { vm.markRead(it); openMessage("message/$box/$it") } }
                }
            }
        }
    }
}

@Composable internal fun MessageRow(m: SecureMessage, sent: Boolean, c: BaytonColors, onClick: () -> Unit) {
    val unread = !sent && m.viewedDate.isNullOrBlank()
    val who = listOfNotNull(m.firstName?.trim(), m.lastName?.trim()).joinToString(" ").ifBlank { "Gwent Music" }
    Column(Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
        .semantics { if (!sent) stateDescription = if (unread) "Unread" else "Read" }
        .padding(horizontal = HPAD.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            MsgAvatar(who, unread, c)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, color = if (unread) c.ink else c.inkDim, fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (unread) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent)) }
                }
                Spacer(Modifier.height(2.dp))
                Text(m.subject?.trim().orEmpty().ifBlank { "(no subject)" }, color = if (unread) c.inkDim else c.inkFaint,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(fmtDate(m.dateStamp), color = if (unread) c.inkFaint else c.inkMute, fontSize = 11.5f.sp)
        }
        HorizontalDivider(color = c.line)
    }
}

/** Round avatar: accent-tinted for unread, muted for read. */
@Composable private fun MsgAvatar(who: String, unread: Boolean, c: BaytonColors) {
    val mod = if (unread) Modifier.background(c.accentSoft).border(1.dp, c.accentLine, CircleShape) else Modifier.background(c.line)
    Box(Modifier.size(42.dp).clip(CircleShape).then(mod), contentAlignment = Alignment.Center) {
        Text(who.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
            color = if (unread) c.accent2 else c.inkFaint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable internal fun Segmented(options: List<String>, selected: Int, c: BaytonColors, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = HPAD.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) c.accent else Color.Transparent)
                .selectable(selected = on, role = Role.Tab, interactionSource = null, indication = null) { onSelect(i) }.padding(vertical = 9.dp), Alignment.Center) {
                Text(label, color = if (on) Color(0xFF0E0D0C) else c.inkFaint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
