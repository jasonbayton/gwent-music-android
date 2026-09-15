package org.bayton.gmc.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.GwentMusicTheme
import org.bayton.gmc.ui.theme.LocalBayton

private const val HPAD = 22

/**
 * Home dashboard content only (MainScaffold provides the bottom bar). The soonest lesson sits in a
 * full-bleed "Next up" band; later lessons follow as an edge-to-edge list with thin dividers;
 * Messages is a separate contained shortcut; a raised band separates the content from the
 * quick-action grid. `open` switches primary tab or pushes a secondary route.
 */
@Composable
fun HomeContent(
    contentPadding: PaddingValues,
    ui: HomeUi,
    open: (String) -> Unit = {},
    onMessageTeacher: (toContactId: String, toName: String) -> Unit = { _, _ -> },
    onRetryOverview: () -> Unit = {},
    onRetryTerm: () -> Unit = {},
    onRetryMessages: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onReconnect: () -> Unit = {},
) {
    val c = LocalBayton.current
    // Background bleeds edge to edge (dark canvas under the status bar); content insets below the
    // status bar and clear of a side navigation bar in landscape. The bottom bar owns its inset.
    Box(Modifier.fillMaxSize().padding(contentPadding)
        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        if (ui.reauthRequired) {
            Centered(c, "Reconnect Gwent Music", "Your session has expired. Sign in again to keep getting updates.") {
                Button(onClick = onReconnect, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) { Text("Reconnect") }
            }
        } else {
            // Each module (upcoming lessons, term progress, messages) loads and renders on its own,
            // so a slow diary query degrades to a single retryable card instead of the whole page.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(10.dp))
                Header(ui.greeting, ui.dateLine, ui.overview, ui.term, ui.freshness, ui.syncedAt, c, onRetryTerm, onRefresh)
                OverviewSection(ui.overview, c, onMessageTeacher, onRetryOverview) { open("schedule") }
                MessagesSection(ui.messages, c, onRetryMessages, open)
                Spacer(Modifier.height(14.dp))
                SeparationBand(c)
                Spacer(Modifier.height(20.dp))
                SectionLabel("Quick actions", c, Modifier.padding(horizontal = HPAD.dp))
                Spacer(Modifier.height(14.dp))
                ActionGrid(c, open)
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Smooth a module's asynchronous arrival. Each module resolves on its own schedule (the diary query
 * is slow), so without this a resolving module would snap its content in and push everything below
 * it down the page. AnimatedContent fades the incoming state in while the height animates to fit, so
 * a module appears in place, and a later refresh cross-fades, instead of jumping the layout.
 */
@Composable private fun <T> ModuleReveal(target: T, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            (fadeIn(tween(durationMillis = 240, delayMillis = 30)) togetherWith
                fadeOut(tween(durationMillis = 130))) using SizeTransform(clip = true) { _, _ -> tween(300) }
        },
        label = "module",
    ) { state -> content(state) }
}

/** Upcoming-lessons module: the soonest lesson as the highlighted card, later lessons as a thin list. */
@Composable private fun OverviewSection(
    overview: Load<Overview>, c: BaytonColors,
    onMessageTeacher: (String, String) -> Unit, onRetry: () -> Unit, onViewSchedule: () -> Unit,
) {
    ModuleReveal(overview) { state ->
        when (state) {
            Load.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 44.dp), Alignment.Center) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
            }
            is Load.Failed -> Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(18.dp))
                InlineRetry("Couldn't load your lessons", null, c, onRetry)
            }
            is Load.Ready -> Column(Modifier.fillMaxWidth()) {
                val value = state.value
                val hero = heroOf(value)
                val rest = if (value.nextUp.size > 1) value.nextUp.drop(1) else emptyList()
                if (hero != null) {
                    Spacer(Modifier.height(22.dp))
                    Hero(hero, c, onMessageTeacher, onViewSchedule)
                } else {
                    Spacer(Modifier.height(18.dp))
                    EmptyNextUp(c)
                }
                rest.forEach { LessonRow(it, c) }
            }
        }
    }
}

/** Messages module: the attention banner. Nothing shows while loading; the banner appears when ready. */
@Composable private fun MessagesSection(messages: Load<List<AttentionRow>>, c: BaytonColors, onRetry: () -> Unit, open: (String) -> Unit) {
    ModuleReveal(messages) { state ->
        when (state) {
            Load.Loading -> Unit
            is Load.Failed -> Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(16.dp))
                InlineRetry("Couldn't load messages", null, c, onRetry)
            }
            is Load.Ready -> state.value.firstOrNull()?.let { row ->
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(16.dp))
                    MessagesBanner(row, c) { open(row.route) }
                }
            } ?: Unit
        }
    }
}

/** A compact, retryable failure card for a single module - the page around it stays usable. */
@Composable private fun InlineRetry(title: String, detail: String?, c: BaytonColors, onRetry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = HPAD.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(c.canvas2).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = c.inkFaint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        // A real button (not a bare clickable) so the failure-recovery action gives press feedback.
        TextButton(onClick = onRetry) { Text("Retry", color = c.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
    }
}

/** Unified hero source: the soonest multi-pupil row, else the single-pupil next lesson. */
private data class Hero(
    val subject: String, val child: String?, val teacher: String?, val where: String?,
    val time: String, val weekday: String, val relative: String, val teacherContactId: String?,
)

private fun heroOf(o: Overview): Hero? =
    o.nextUp.firstOrNull()?.let { l ->
        Hero(l.subject, l.child.ifBlank { null }, l.teacher, l.location, l.timeLabel, l.dateLabel, l.relative, l.teacherContactId)
    } ?: o.next?.let { n ->
        Hero(n.subject, null, n.teacher, listOfNotNull(n.location, n.room).joinToString(" · ").ifBlank { null },
            n.timeLabel, n.dateLabel, n.relative, n.teacherContactId)
    }

@Composable private fun Centered(c: BaytonColors, title: String, body: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = c.ink, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(body, color = c.inkDim, textAlign = TextAlign.Center, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp)); action()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun Header(
    greeting: String, dateLine: String, overview: Load<Overview>, term: Load<TermProgress>,
    freshness: org.bayton.gmc.data.cache.Freshness, syncedAt: Long?,
    c: BaytonColors, onRetryTerm: () -> Unit, onRefresh: () -> Unit,
) {
    // The name resolves with the overview module; until then the greeting stands alone.
    val name = (overview as? Load.Ready)?.value?.who?.takeIf { it.isNotBlank() }
    Column(Modifier.padding(horizontal = HPAD.dp)) {
        // The name arrives with the overview module; animate its insertion so the greeting doesn't
        // hop up to make room for it.
        Column(Modifier.animateContentSize(tween(240))) {
            if (name != null) {
                Text(greeting, color = c.inkFaint, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(name, color = c.ink, style = MaterialTheme.typography.displaySmall)
            } else {
                Text(greeting, color = c.ink, style = MaterialTheme.typography.displaySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dots are on their own line now, so the date gets the full width; it only
            // ellipsises if it genuinely can't fit, and the hairline fills whatever is left.
            Text(dateLine, color = c.inkFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
        }
        org.bayton.gmc.ui.components.FreshnessPill(freshness, syncedAt, Modifier.padding(top = 8.dp), onRetry = onRefresh)
        // Term progress: a dot per planned lesson this term, the completed ones filled accent. The
        // dots wrap (a busy multi-pupil term can be many) and the count carries the exact figure.
        // Loads on its own (the slow query): quiet while loading, retryable if it fails.
        ModuleReveal(term) { state ->
            when (state) {
                Load.Loading -> Unit
                is Load.Failed -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Term progress unavailable", color = c.inkMute, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Retry", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        // Bounded ripple + a small touch target, without the weight of a full button on this caption row.
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRetryTerm).padding(horizontal = 6.dp, vertical = 3.dp))
                }
                is Load.Ready -> if (state.value.total > 0) Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(state.value.total) { i ->
                            Box(Modifier.size(6.dp).background(if (i < state.value.completed) c.accent else c.inkMute, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${state.value.completed} of ${state.value.total} lessons", color = c.inkFaint, fontSize = 11.sp)
                } else Unit
            }
        }
    }
}

@Composable private fun Hero(h: Hero, c: BaytonColors, onMessageTeacher: (String, String) -> Unit, onViewSchedule: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.accentSoft, Color.Transparent))),
    ) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.accent))
        Column(Modifier.padding(horizontal = HPAD.dp).padding(top = 16.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT UP", color = c.accent2, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, letterSpacing = 2.sp)
                FilledPill(h.relative, c)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(h.subject, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (h.child != null) {
                            Text(h.child, color = c.accent2, fontWeight = FontWeight.SemiBold, fontSize = 13.5f.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                            h.teacher?.let { Text(" · $it", color = c.inkDim, fontSize = 13.5f.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                        } else h.teacher?.let { Text(it, color = c.inkDim, fontSize = 13.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    h.where?.let { Text(it, color = c.inkFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(h.time, color = c.ink, fontSize = 30.sp,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold))
                    Text(h.weekday.uppercase(), color = c.inkFaint, fontSize = 12.5f.sp, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("Message teacher", color = c.accent2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        onMessageTeacher(h.teacherContactId.orEmpty(), h.teacher.orEmpty())
                    })
                Text("View schedule", color = c.inkFaint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onViewSchedule))
            }
        }
        HorizontalDivider(color = c.line)
    }
}

/** A later lesson: flat, edge-to-edge, separated by a hairline - never a bordered card. */
@Composable private fun LessonRow(l: UpcomingLesson, c: BaytonColors) {
    Column(Modifier.padding(horizontal = HPAD.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(c.line), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MusicNote, null, tint = c.accent2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (l.child.isNotBlank()) {
                        Text(l.child, color = c.accent2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                        Text(" · ${l.subject}", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    } else Text(l.subject, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val sub = listOfNotNull(l.teacher, l.location).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = c.inkFaint, fontSize = 12.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${l.dateLabel} ${l.timeLabel}", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(l.relative, color = c.inkMute, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable private fun MessagesBanner(r: AttentionRow, c: BaytonColors, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = HPAD.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(c.canvas2).clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Mail, null, tint = c.accent2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (r.count > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.defaultMinSize(19.dp, 19.dp).clip(CircleShape).background(c.accent).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                        Text("${r.count}", color = Color(0xFF0E0D0C), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(r.detail, color = c.inkFaint, fontSize = 12.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.inkMute, modifier = Modifier.size(20.dp))
    }
}

/** A raised band separating the content above from the actions and navigation below. */
@Composable private fun SeparationBand(c: BaytonColors) =
    Box(Modifier.fillMaxWidth().height(9.dp).background(c.sink))

@Composable private fun EmptyNextUp(c: BaytonColors) {
    Column(Modifier.padding(horizontal = HPAD.dp)) {
        Text("No upcoming lessons", color = c.inkDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text("New lessons appear here as they're scheduled.", color = c.inkFaint, fontSize = 13.sp)
    }
}

@Composable private fun SectionLabel(t: String, c: BaytonColors, modifier: Modifier = Modifier) =
    Text(t.uppercase(), color = c.inkMute, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, modifier = modifier)

@Composable private fun FilledPill(t: String, c: BaytonColors) = Box(
    Modifier.clip(RoundedCornerShape(999.dp)).background(c.accent).padding(horizontal = 9.dp, vertical = 3.dp),
) { Text(t.uppercase(), color = Color(0xFF0E0D0C), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }

private data class Action(val icon: ImageVector, val label: String, val route: String)

@Composable private fun ActionGrid(c: BaytonColors, open: (String) -> Unit) {
    val actions = listOf(
        Action(Icons.Outlined.Forum, "Messages", "messages"),
        Action(Icons.Outlined.CalendarMonth, "Schedule", "schedule"),
        Action(Icons.AutoMirrored.Outlined.Assignment, "Reports", "reports"),
        Action(Icons.Outlined.Group, "Pupils", "pupils"),
        Action(Icons.Outlined.Payments, "Billing", "billing"),
        Action(Icons.Outlined.School, "Teacher", "teacher"),
        Action(Icons.Outlined.Settings, "Settings", "settings"),
    )
    Column(Modifier.padding(horizontal = HPAD.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        actions.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a -> Box(Modifier.weight(1f)) { Tile(a, c, open) } }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }
}

@Composable private fun Tile(a: Action, c: BaytonColors, open: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = null, indication = null) { open(a.route) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(a.icon, null, tint = c.inkDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(7.dp))
        Text(a.label, color = c.inkDim, fontSize = 11.sp)
    }
}

@androidx.compose.ui.tooling.preview.Preview @Composable
private fun HomePreview() = GwentMusicTheme { HomeContent(PaddingValues(0.dp), sampleHome()) }

private fun sampleHome() = HomeUi(
    greeting = "Good evening",
    dateLine = "Fri 5 Sep · Autumn term",
    overview = Load.Ready(Overview(
        who = "Mikael & Sofia's lessons",
        nextUp = listOf(
            UpcomingLesson("Sofia", "Strings", "Anna Price", "Central Studio", "Wed", "16:00", "tomorrow"),
            UpcomingLesson("Mikael", "Brass", "Christopher Turner", "Bassaleg School", "Thu", "08:00", "in 6 days"),
        ),
    )),
    term = Load.Ready(TermProgress(total = 13, completed = 4)),
    messages = Load.Ready(listOf(AttentionRow("mail", "Messages", "Autumn Timetable · Trumpet", 2, "messages"))),
)
