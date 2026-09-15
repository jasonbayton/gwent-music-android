package org.bayton.gmc.ui.pupils

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.School
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
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton

@Composable
fun PupilActivitiesScreen(onBack: () -> Unit, vm: PupilActivitiesViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    val year = selectedYear ?: ui.years.firstOrNull()

    GmcScaffold(title = vm.pupilName.ifBlank { "Activities" }, onBack = onBack) { pad ->
        StateBox(ui.loading, ui.error, ui.applications.isEmpty() && ui.byYear.isEmpty(), "No activities yet", pad, retry = vm::load) {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.applications.isNotEmpty()) {
                    item("apps-label") { SectionLabel("Current applications") }
                    items(ui.applications, key = { "app:${it.id}" }) { ActivityCard(it, c) }
                    item("apps-gap") { Spacer(Modifier.height(4.dp)) }
                }

                if (ui.years.isNotEmpty()) {
                    item("years") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.years.forEach { y -> YearChip(y, y == year, c) { selectedYear = y } }
                        }
                    }
                    val rows = year?.let { ui.byYear[it] }.orEmpty()
                    item("year-label") { SectionLabel(academicYearLabel(year)) }
                    items(rows, key = { "act:${it.id}" }) { ActivityCard(it, c) }
                }
            }
        }
    }
}

@Composable private fun YearChip(year: Int, selected: Boolean, c: BaytonColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) c.accent else c.surface)
            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(999.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            if (year == 0) "Unknown" else year.toString(),
            color = if (selected) androidx.compose.ui.graphics.Color(0xFF0E0D0C) else c.inkDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable private fun ActivityCard(a: PupilActivity, c: BaytonColors) {
    val icon: ImageVector = if (a.hireRequired) Icons.Outlined.Key else Icons.Outlined.School
    BaytonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft), Alignment.Center) {
                Icon(icon, null, tint = c.accent2, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.activity.ifBlank { "Tuition" }, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                a.statusLine()?.let { Text(it, color = c.accent2, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
            when {
                a.progressed -> Pill("Progressed", PillTone.Muted)
                a.ref.isNotBlank() -> Pill(a.ref, PillTone.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (a.venue.isNotBlank()) InfoLine(Icons.Outlined.Place, a.venue, c)
        a.teacher?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.Outlined.Person, it, c) }
        a.startDate?.let { InfoLine(Icons.Outlined.CalendarMonth, "Started ${fmtDate(it)}", c) }
    }
}

@Composable private fun InfoLine(icon: ImageVector, text: String, c: BaytonColors) {
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.inkFaint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.inkDim, fontSize = 13.sp)
    }
}

private fun PupilActivity.statusLine(): String? {
    if (statusLabel.isBlank()) return null
    val start = startDate?.let { fmtDate(it) }.orEmpty()
    return if (accepted && start.isNotBlank()) "$statusLabel - starting $start" else statusLabel
}

private fun academicYearLabel(year: Int?): String = when (year) {
    null -> "Enrolments"
    0 -> "Unknown date"
    else -> "$year/${((year + 1) % 100).toString().padStart(2, '0')} academic year"
}
