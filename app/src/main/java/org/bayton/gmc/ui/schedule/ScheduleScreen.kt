package org.bayton.gmc.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.data.AcademicTerms
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.ui.components.*
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val HPAD = 22

/**
 * Agenda scoped to the current term. Upcoming shows everything ahead, with a term divider whenever
 * lessons span more than one term; Past shows only the current term's lessons already gone (so it is
 * empty until the term's first lesson passes). Full history lives behind the history icon.
 */
@Composable
fun ScheduleContent(contentPadding: PaddingValues, onOpenHistory: () -> Unit = {}, vm: ScheduleViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showPast by rememberSaveable { mutableStateOf(false) }
    val now = LocalDateTime.now()

    val multiPupil = ui.items.mapNotNull { it.firstName?.trim()?.ifBlank { null } }.distinct().size > 1
    // Labels come from each term's FULL span (all its lessons); display filters happen afterwards, so
    // the header, the Upcoming dividers and History all name a term the same way. Recomputed each pass
    // so the current term follows the clock, not only data changes.
    val groups = AcademicTerms.group(ui.items)
    val currentTerm = AcademicTerms.currentTerm(ui.items, now)
    val currentGroup = groups.firstOrNull { it.key == currentTerm }
    val termLabel = currentGroup?.label

    Column(Modifier.fillMaxSize().padding(contentPadding).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = HPAD.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Schedule", color = c.ink, style = MaterialTheme.typography.displaySmall)
                termLabel?.let { Text(it, color = c.accent2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.canvas2).border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .clickable(interactionSource = null, indication = null, onClick = onOpenHistory),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.History, "Lesson history", tint = c.inkDim, modifier = Modifier.size(21.dp)) }
        }
        org.bayton.gmc.ui.components.FreshnessPill(ui.freshness, ui.syncedAt, Modifier.padding(horizontal = HPAD.dp).padding(top = 6.dp), onRetry = vm::load)
        Spacer(Modifier.height(16.dp))
        Segmented(listOf("Upcoming", "Past"), if (showPast) 1 else 0, c) { showPast = it == 1 }
        Spacer(Modifier.height(4.dp))
        // A later window failed while the near term loaded: the agenda is usable but incomplete.
        if (ui.partial && !ui.loading && ui.error == null) PartialNote(c, onRetry = vm::load)

        // Past = the current term's lessons already gone (empty until the term's first lesson passes).
        val past = currentGroup?.lessons.orEmpty()
            .mapNotNull { d -> AcademicTerms.parse(d.startDateTime)?.let { it to d } }
            .filter { it.first.isBefore(now) }
            .sortedByDescending { it.first }.map { it.second }
        // Upcoming = every term's future lessons, terms kept in date order; drop terms with nothing ahead.
        val upcomingByTerm = groups.map { g ->
            g to g.lessons.mapNotNull { d -> AcademicTerms.parse(d.startDateTime)?.let { it to d } }
                .filter { !it.first.isBefore(now) }.sortedBy { it.first }.map { it.second }
        }.filter { it.second.isNotEmpty() }
        val showTermHeaders = upcomingByTerm.size > 1

        val empty = if (showPast) past.isEmpty() else upcomingByTerm.isEmpty()
        val emptyText = if (showPast) "No past lessons this term yet" else "No lessons scheduled"
        StateBox(ui.loading, ui.error, empty, emptyText, PaddingValues(), retry = vm::load) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)) {
                if (showPast) {
                    agenda(past, c, multiPupil, descending = true, keyPrefix = "past")
                } else {
                    upcomingByTerm.forEach { (g, lessons) ->
                        if (showTermHeaders) item("term-${g.key}") { TermDivider(g.label, lessons.size, c) }
                        agenda(lessons, c, multiPupil, descending = false, keyPrefix = g.key.toString())
                    }
                }
            }
        }
    }
}

/**
 * Emit day-grouped lesson rows into a LazyColumn, ascending or descending. Every key is prefixed with
 * the caller's `keyPrefix` (the term) and a within-day index, so two terms sharing a date - or two
 * pupils' same-time lessons - can never collide on a LazyColumn key.
 */
internal fun LazyListScope.agenda(lessons: List<DiaryEntry>, c: BaytonColors, multiPupil: Boolean, descending: Boolean, keyPrefix: String) {
    val dated = lessons.mapNotNull { d -> AcademicTerms.parse(d.startDateTime)?.let { it to d } }
    val byDay = dated.groupBy { it.first.toLocalDate() }
    val days = byDay.keys.sortedWith(if (descending) compareByDescending { it } else compareBy { it })
    days.forEach { day ->
        item("h-$keyPrefix-$day") { DayHeader(day, c) }
        val rows = byDay.getValue(day).let { if (descending) it.sortedByDescending { r -> r.first } else it.sortedBy { r -> r.first } }.map { it.second }
        // A diary ID identifies the lesson, not necessarily its pupil record. Always include the
        // within-day index, including for non-null IDs shared by pupils attending the same lesson.
        itemsIndexed(rows, key = { idx, e -> "$keyPrefix-$day-${e.diaryID}-$idx" }) { _, e -> LessonRow(e, c, multiPupil) }
    }
}

/** Shown when the near term loaded but a wider window failed: the agenda is usable but incomplete. */
@Composable private fun PartialNote(c: BaytonColors, onRetry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = HPAD.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(11.dp)).background(c.canvas2).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Some lessons couldn't be loaded", color = c.inkDim, fontSize = 12.5f.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("Retry", color = c.accent, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRetry).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable private fun TermDivider(label: String, count: Int, c: BaytonColors) {
    Column(Modifier.padding(start = HPAD.dp, end = HPAD.dp, top = 18.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.ink, style = MaterialTheme.typography.displaySmall.copy(fontSize = 17.sp))
            Spacer(Modifier.weight(1f))
            Text("$count ${if (count == 1) "lesson" else "lessons"}", color = c.inkFaint, fontSize = 12.5f.sp)
        }
    }
}

@Composable private fun DayHeader(day: LocalDate, c: BaytonColors) {
    val today = LocalDate.now()
    val date = day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    val label = when (day) {
        today -> "TODAY · $date"; today.plusDays(1) -> "TOMORROW · $date"; today.minusDays(1) -> "YESTERDAY · $date"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMMM")).uppercase()
    }
    val near = day == today || day == today.plusDays(1)
    Row(Modifier.padding(start = HPAD.dp, end = HPAD.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (near) c.accent2 else c.inkFaint, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}

/** Flat time-rail row: never a bordered card - rows are separated by hairlines, edge to edge. */
@Composable private fun LessonRow(d: DiaryEntry, c: BaytonColors, showChild: Boolean) {
    val dt = AcademicTerms.parse(d.startDateTime)
    val (subject, teacher) = org.bayton.gmc.data.DiaryText.subjectAndTeacher(d.subject)
    val child = d.firstName?.trim()?.ifBlank { null }
    val mins = d.activityRateDuration ?: d.duration
    Column(Modifier.padding(horizontal = HPAD.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(52.dp)) {
                Text(dt?.format(TIME).orEmpty(), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                mins?.let { Text("$it min", color = c.inkMute, fontSize = 11.sp) }
            }
            Box(Modifier.padding(end = 14.dp).width(1.dp).height(38.dp).background(c.line2))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subject, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (showChild && child != null) { Spacer(Modifier.width(8.dp)); ChildChip(child, c) }
                }
                Spacer(Modifier.height(3.dp))
                Text(listOfNotNull(d.location, teacher).joinToString(" · ").ifBlank { "Lesson" }, color = c.inkFaint, fontSize = 12.5f.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (d.virtual) { Spacer(Modifier.width(8.dp)); Pill("Online", PillTone.Muted) }
            else if (!d.subLocation.isNullOrBlank()) { Spacer(Modifier.width(8.dp)); RoomTag(d.subLocation, c) }
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable private fun ChildChip(name: String, c: BaytonColors) = Box(
    Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).border(1.dp, c.accentLine, RoundedCornerShape(999.dp))
        .padding(horizontal = 8.dp, vertical = 2.dp),
) { Text(name.uppercase(), color = c.accent2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }

@Composable private fun RoomTag(room: String, c: BaytonColors) = Row(
    Modifier.clip(RoundedCornerShape(7.dp)).background(c.canvas2).border(1.dp, c.line, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Icons.Outlined.Place, null, tint = c.inkFaint, modifier = Modifier.size(11.dp))
    Spacer(Modifier.width(4.dp))
    Text(room, color = c.inkDim, fontSize = 10.5f.sp, fontWeight = FontWeight.Medium)
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

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
