package org.bayton.gmc.ui.schedule

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bayton.gmc.data.AcademicTerms
import org.bayton.gmc.data.DiaryText
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.HistoryPager
import org.bayton.gmc.data.HistoryBatches
import org.bayton.gmc.data.TermGroup
import org.bayton.gmc.data.TermKey
import org.bayton.gmc.data.model.DiaryEntry
import org.bayton.gmc.ui.components.GmcScaffold
import org.bayton.gmc.ui.components.StateBox
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HistoryUi(
    val terms: List<TermGroup> = emptyList(), val multiPupil: Boolean = false,
    val loading: Boolean = true, val error: String? = null,
    val loadedFrom: LocalDate? = null, val hasMore: Boolean = true,
    val unboundedKeys: Set<TermKey> = emptySet(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val today = LocalDate.now()
    // No arbitrary retention cutoff. Each action has a bounded request budget, but the user can
    // continue searching older dates rather than an empty period being mistaken for EOF.
    private val pager = HistoryPager(today, LocalDate.of(1, 1, 1), repo::scheduleHistory)
    private val batches = HistoryBatches(pager, LocalDateTime.now(), repo::historyTermDates)
    val ui = batches.state.map { page ->
            val now = LocalDateTime.now()
            val all = page.entries
            val multi = all.mapNotNull { it.firstName?.trim()?.ifBlank { null } }.distinct().size > 1
            // Merge pages before grouping. A term spanning a page boundary grows as older data
            // arrives; labels and counts describe the loaded portion until its full span is known.
            val terms = AcademicTerms.group(all)
                .map { g -> g to g.lessons.filter { AcademicTerms.parse(it.startDateTime)?.isBefore(now) == true } }
                .filter { it.second.isNotEmpty() }
                .map { (g, past) -> g.copy(lessons = past) }
                .sortedByDescending { it.start }
            HistoryUi(terms, multi, page.loading, page.error, page.loadedFrom, page.hasMore, page.unboundedKeys)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUi())
    init { load() }
    fun load() { viewModelScope.launch { batches.loadMore() } }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    HistoryContent(ui, onBack, vm::load)
}

/** Rendering never requests another page implicitly; only the footer's explicit actions do. */
@Composable
internal fun HistoryContent(ui: HistoryUi, onBack: () -> Unit, loadMore: () -> Unit) {
    val c = LocalBayton.current
    // The most recent term starts expanded; the rest collapsed.
    val expanded = remember { mutableStateMapOf<TermKey, Boolean>() }
    LaunchedEffect(ui.terms) { ui.terms.firstOrNull()?.let { if (expanded.isEmpty()) expanded[it.key] = true } }

    GmcScaffold(title = "History", onBack = onBack) { pad ->
        StateBox(ui.loading && ui.loadedFrom == null, ui.error.takeIf { ui.loadedFrom == null },
            ui.terms.isEmpty() && !ui.hasMore, "No past lessons found", pad, retry = loadMore) {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.terms, key = { it.key.toString() }) { g ->
                    TermSection(g, expanded[g.key] == true, ui.multiPupil, c, g.key in ui.unboundedKeys) { expanded[g.key] = !(expanded[g.key] ?: false) }
                }
                item(key = "history-pagination") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ui.loadedFrom?.let { Text("Searched back to $it", color = c.inkDim) }
                        when {
                            ui.error != null -> {
                                Text(ui.error!!, color = c.inkDim)
                                TextButton(onClick = loadMore) { Text("Retry these terms") }
                            }
                            ui.loading -> {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text("Loading older lessons...", color = c.inkDim)
                            }
                            ui.hasMore -> {
                                if (ui.terms.isEmpty()) Text("No lessons in the period searched.", color = c.inkDim)
                                Text("Loads up to four more terms.", color = c.inkDim)
                                TextButton(onClick = loadMore) { Text("Load more") }
                            }
                            else -> Text("No earlier dates to search", color = c.inkDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TermSection(g: TermGroup, open: Boolean, multiPupil: Boolean, c: BaytonColors, unbounded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.canvas2).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onToggle).padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("${g.lessons.size} ${if (g.lessons.size == 1) "lesson" else "lessons"}", color = c.inkFaint, fontSize = 12.5f.sp)
                if (unbounded) Text("Loaded lessons only", color = c.inkDim, fontSize = 12.5f.sp)
            }
            Icon(Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand", tint = c.inkDim, modifier = Modifier.size(22.dp).rotate(rotation))
        }
        if (open) {
            HorizontalDivider(color = c.line)
            // Most recent lesson first within the term.
            g.lessons.sortedByDescending { it.startDateTime }.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 16.dp))
                HistoryRow(e, multiPupil, c)
            }
        }
    }
}

@Composable private fun HistoryRow(d: DiaryEntry, multiPupil: Boolean, c: BaytonColors) {
    val dt = AcademicTerms.parse(d.startDateTime)
    val (subject, teacher) = DiaryText.subjectAndTeacher(d.subject)
    val child = d.firstName?.trim()?.ifBlank { null }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(66.dp)) {
            Text(dt?.format(DATE).orEmpty(), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(dt?.format(TIME).orEmpty(), color = c.inkMute, fontSize = 11.5f.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subject, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (multiPupil && child != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(child.uppercase(), color = c.accent2, fontSize = 9.5f.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            }
            val sub = listOfNotNull(d.location, teacher).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = c.inkFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yy")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
