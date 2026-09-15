package org.bayton.gmc.ui.pupils

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.LocalBayton

@Composable
fun PupilsScreen(onBack: () -> Unit, onOpen: (pupilId: String, name: String) -> Unit, vm: PupilsViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    GmcScaffold(title = "Pupils", onBack = onBack) { pad ->
        StateBox(ui.loading, ui.error, ui.items.isEmpty(), "No pupils linked", pad, retry = vm::load) {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(ui.items) { p ->
                    val name = listOfNotNull(p.firstName?.trim(), p.lastName?.trim()).joinToString(" ").ifBlank { "Pupil" }
                    BaytonCard(onClick = { p.pupilID?.let { onOpen(it, name) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name, size = 42)
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("View activities", color = c.inkFaint, fontSize = 12.sp)
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.inkFaint)
                        }
                    }
                }
            }
        }
    }
}
