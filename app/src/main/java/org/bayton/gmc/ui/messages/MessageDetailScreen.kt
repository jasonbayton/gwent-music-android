package org.bayton.gmc.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.model.SecureMessage
import org.bayton.gmc.ui.components.GmcScaffold
import org.bayton.gmc.ui.components.fmtDateTime
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import javax.inject.Inject

@HiltViewModel
class MessageDetailViewModel @Inject constructor(private val repo: GwentRepository, handle: SavedStateHandle) : ViewModel() {
    private val box: String = handle["box"] ?: "recv"
    private val itemId: String = handle["itemId"] ?: ""
    val isSent: Boolean = box == "sent"
    private val _message = MutableStateFlow<SecureMessage?>(null)
    val message = _message.asStateFlow()
    private val _body = MutableStateFlow<String?>(null)
    val body = _body.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    init {
        viewModelScope.launch {
            val m = repo.messageByItem(box, itemId)
            _message.value = m
            _loading.value = false
            if (m != null) {
                _body.value = repo.messageBody(m) ?: ""
                if (!isSent && m.viewedDate.isNullOrBlank()) {
                    repo.markRead(itemId)                 // local, immediate
                    repo.markReadRemote(itemId) // Best-effort, with sanitised failure diagnostics.
                }
            }
        }
    }
}

@Composable
fun MessageDetailScreen(onBack: () -> Unit, onReply: (subject: String, toContactId: String, toName: String) -> Unit, vm: MessageDetailViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val m by vm.message.collectAsStateWithLifecycle()
    val body by vm.body.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val who = listOfNotNull(m?.firstName?.trim(), m?.lastName?.trim()).joinToString(" ").ifBlank { "Gwent Music" }

    GmcScaffold(
        title = if (vm.isSent) "Sent" else "Message",
        onBack = onBack,
        actions = {
            val cur = m
            if (!vm.isSent && cur != null) TextButton(onClick = { onReply(cur.subject.orEmpty(), cur.fromContactID.orEmpty(), who) }) {
                Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = c.accent2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp)); Text("Reply", color = c.accent2, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { pad ->
        if (loading) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }; return@GmcScaffold }
        val msg = m ?: run { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Text("Message not available", color = c.inkFaint) }; return@GmcScaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(msg.subject?.trim().orEmpty().ifBlank { "(no subject)" }, color = c.ink,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 22.sp, lineHeight = 27.sp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundAvatar(who, c)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(who, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5f.sp)
                    Text(fmtDateTime(msg.dateStamp), color = c.inkFaint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = c.line)
            Spacer(Modifier.height(20.dp))
            when (val b = body) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Loading message...", color = c.inkFaint, fontSize = 13.sp)
                }
                else -> Text(b.ifBlank { "(no content)" }, color = c.inkDim, fontSize = 15.sp, lineHeight = 26.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable private fun RoundAvatar(who: String, c: BaytonColors) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft).border(1.dp, c.accentLine, CircleShape),
    contentAlignment = Alignment.Center,
) {
    Text(who.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
        color = c.accent2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}
