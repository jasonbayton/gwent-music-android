package org.bayton.gmc.ui.teacher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import javax.inject.Inject

data class Teacher(val contactId: String, val name: String)
data class Recipient(val contactId: String, val name: String, val subtitle: String)
data class TeacherUi(val recipients: List<Recipient> = emptyList(), val loading: Boolean = true, val error: String? = null)

@HiltViewModel
class TeacherViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(TeacherUi())
    val ui = _ui.asStateFlow()
    init { load() }
    fun load() = viewModelScope.launch {
        _ui.value = TeacherUi(loading = true)
        _ui.value = runCatching { repo.messageRecipients() }
            .fold({ TeacherUi(recipients = it, loading = false) }, { TeacherUi(loading = false, error = it.message ?: "Could not load contacts") })
    }
}

@Composable
fun TeacherScreen(onBack: () -> Unit, onMessage: (contactId: String, name: String) -> Unit, vm: TeacherViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    GmcScaffold(title = "New message", onBack = onBack) { pad ->
        StateBox(ui.loading, ui.error, ui.recipients.isEmpty(), "No one to message yet", pad, retry = vm::load) {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                item {
                    Text("Choose who to message.", color = c.inkFaint, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
                }
                items(ui.recipients) { t -> RecipientRow(t, c) { onMessage(t.contactId, t.name) } }
            }
        }
    }
}

@Composable private fun RecipientRow(t: Recipient, c: BaytonColors, onClick: () -> Unit) {
    Column(Modifier.clickable(interactionSource = null, indication = null, onClick = onClick).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.line), contentAlignment = Alignment.Center) {
                Text(t.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
                    color = c.inkDim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(t.subtitle, color = c.inkFaint, fontSize = 12.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.inkMute, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(color = c.line)
    }
}
