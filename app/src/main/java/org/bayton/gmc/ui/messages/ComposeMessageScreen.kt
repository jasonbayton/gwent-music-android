package org.bayton.gmc.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.ui.components.GmcScaffold
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import javax.inject.Inject

data class ComposeUi(val sending: Boolean = false, val sent: Boolean = false, val error: String? = null)

@HiltViewModel
class ComposeViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ComposeUi())
    val ui = _ui.asStateFlow()
    fun send(subject: String, body: String, toContactId: String) = viewModelScope.launch {
        // Local duplicate-submit guard while sending or after confirmed success. This is not
        // server-side idempotency: an uncertain failure or a recreated ViewModel permits another try.
        val s = _ui.value
        if (s.sending || s.sent) return@launch
        if (toContactId.isBlank()) {
            _ui.value = ComposeUi(error = "No recipient. Open a message and use Reply to contact that teacher.")
            return@launch
        }
        _ui.value = ComposeUi(sending = true)
        val ok = try {
            repo.sendMessage(subject.trim(), body.trim(), toContactId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A destination teardown must not publish a new UI error from its cancelled request.
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // The vendor send has no idempotency key, so a failure may still have reached the server.
        // Be honest rather than inviting a blind resend that could duplicate the message.
        _ui.value = if (ok) ComposeUi(sent = true)
        else ComposeUi(error = "We couldn't confirm your message was sent. Check your Sent items before sending again.")
    }
}

/**
 * Owns a destination-scoped draft, while [ComposeViewModel] owns the in-flight send state.
 * Subject and body survive saved-state restoration, but are not a persistent draft mailbox after
 * leaving this destination. The confirmation dialog is intentionally transient: recreation must
 * not itself trigger sending. Navigation callbacks are supplied by the destination owner so async
 * completion cannot pop an unrelated screen.
 */
@Composable
fun ComposeMessageScreen(
    prefillSubject: String,
    toContactId: String,
    toName: String,
    onBack: () -> Unit,
    onSent: () -> Unit,
    vm: ComposeViewModel = hiltViewModel(),
) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Navigation owns a separate saveable-state scope for each compose destination. Preserve the
    // user's draft through recreation, but reset it if the recipient/prefill changes in this scope.
    var subject by rememberSaveable(toContactId, prefillSubject) { mutableStateOf(prefillSubject) }
    var body by rememberSaveable(toContactId, prefillSubject) { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val hasRecipient = toContactId.isNotBlank()
    LaunchedEffect(ui.sent) { if (ui.sent) onSent() }

    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        confirmButton = {
            TextButton(onClick = { confirming = false; vm.send(subject, body, toContactId) }) { Text("Send", color = c.accent2, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel", color = c.inkDim) } },
        title = { Text("Send this message?", color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("To: ${toName.ifBlank { "your teacher" }}", color = c.inkDim, fontSize = 13.sp)
                Text(subject.trim().ifBlank { "(no subject)" }, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(body.trim(), color = c.inkDim, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        },
        containerColor = c.surface,
    )

    val sendAction: @Composable () -> Unit = {
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { confirming = true },
            enabled = hasRecipient && !ui.sending && !ui.sent && subject.isNotBlank() && body.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color(0xFF0E0D0C),
                disabledContainerColor = c.surface2, disabledContentColor = c.inkMute),
            shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (ui.sending) CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF0E0D0C), strokeWidth = 2.dp)
            else Text("Send", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
    GmcScaffold(title = if (hasRecipient) "Reply" else "New message", onBack = onBack) { pad ->
        // Consume the scaffold's system-bar padding before adding only the remaining IME inset.
        // Pin Send when there is room. In very short windows, let it scroll with the form rather
        // than reserving all available space for the button and squeezing the editor to zero.
        BoxWithConstraints(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()
            .fillMaxSize().padding(horizontal = 22.dp)) {
            val compact = maxHeight < 180.dp
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(4.dp))
                    if (hasRecipient) RecipientChip(toName.ifBlank { "your teacher" }, c)
                    else Text(
                        "Replies go to the teacher who messaged you. Open a message and tap Reply to contact them.",
                        color = c.inkFaint, fontSize = 12.5f.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    FieldLabel("Subject", c)
                    NocturneField(subject, { subject = it }, "Subject", "Subject", c, singleLine = true)
                    Spacer(Modifier.height(16.dp))
                    FieldLabel("Message", c)
                    NocturneField(body, { body = it }, "Write your message...", "Message", c, singleLine = false,
                        modifier = Modifier.heightIn(min = 180.dp))
                    ui.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = c.red, fontSize = 13.sp) }
                    if (compact) sendAction()
                }
                if (!compact) sendAction()
            }
        }
    }
}

@Composable private fun RecipientChip(name: String, c: BaytonColors) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).border(1.dp, c.accentLine, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(c.accentSoft).border(1.dp, c.accentLine, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
            color = c.accent2, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
    Spacer(Modifier.width(10.dp))
    Text("To ", color = c.inkFaint, fontSize = 12.sp)
    Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun FieldLabel(text: String, c: BaytonColors) =
    Text(text, color = c.inkFaint, fontSize = 12.5f.sp, modifier = Modifier.padding(bottom = 7.dp))

/** Label-above field on the DS field surface - no floating-label notch. Focus lifts the border to accent. */
@Composable private fun NocturneField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, label: String, c: BaytonColors,
    singleLine: Boolean, modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.heightIn(min = 140.dp))
            .clip(RoundedCornerShape(12.dp)).background(c.canvas2)
            .border(1.dp, if (focused) c.accentLine else c.line, RoundedCornerShape(12.dp))
            // The visible label is a sibling; associate it with the editable node for TalkBack.
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = c.inkMute, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = TextStyle(color = c.ink, fontSize = 15.sp, lineHeight = if (singleLine) 20.sp else 24.sp),
            cursorBrush = SolidColor(c.accent), singleLine = singleLine,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            modifier = (if (singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).onFocusChanged { focused = it.isFocused },
        )
    }
}
