package org.bayton.gmc.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.LocalBayton

@Composable
fun ReportsScreen(onBack: () -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    GmcScaffold(title = "Reports", onBack = onBack) { pad ->
        StateBox(ui.loading, ui.error, ui.items.isEmpty(), "No reports yet", pad, retry = vm::load) {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                items(ui.items) { r ->
                    Column(Modifier.padding(horizontal = 22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.line), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Description, null, tint = c.accent2, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text((r.title ?: r.reportName ?: "Report").trim(), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(listOfNotNull(r.pupilName, r.reportPeriod).joinToString(" · ").ifBlank { fmtDate(r.publishedDate ?: r.dateStamp) }, color = c.inkFaint, fontSize = 12.5f.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(fmtDate(r.publishedDate ?: r.dateStamp), color = c.inkMute, fontSize = 11.5f.sp)
                        }
                        HorizontalDivider(color = c.line)
                    }
                }
            }
        }
    }
}
