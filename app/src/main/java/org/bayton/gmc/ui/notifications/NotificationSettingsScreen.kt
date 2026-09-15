package org.bayton.gmc.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.bayton.gmc.ui.components.BaytonCard
import org.bayton.gmc.ui.components.GmcScaffold
import org.bayton.gmc.ui.components.SectionLabel
import org.bayton.gmc.ui.theme.BaytonColors
import org.bayton.gmc.ui.theme.LocalBayton
import org.bayton.gmc.ui.theme.ThemeController
import org.bayton.gmc.ui.theme.ThemeMode

private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: NotificationSettingsViewModel = hiltViewModel()) {
    val c = LocalBayton.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) }   // label + current minutes

    GmcScaffold(title = "Settings", onBack = onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupHeader("Appearance", c)
            BaytonCard {
                Text("Theme", color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("System", "Light", "Dark"), ThemeController.mode.value.ordinal, c) {
                    ThemeController.set(ctx, ThemeMode.entries[it])
                }
            }

            GroupHeader("Notifications", c, top = 14.dp)
            SectionLabel("Messages")
            BaytonCard { ToggleRow("New messages", "Alert when a new message arrives", ui.messages, c) { vm.setMessages(it) } }

            SectionLabel("Lesson reminders")
            BaytonCard {
                ToggleRow("Morning reminder", "Don't forget your instrument, on lesson days", ui.morning, c) { vm.setMorning(it) }
                if (ui.morning) TimeRow("Reminder time", hhmm(ui.morningMin), c) { picker = "morning" to ui.morningMin }
            }
            BaytonCard {
                ToggleRow("Day-before digest", "An evening summary of tomorrow's lessons", ui.dayBefore, c) { vm.setDayBefore(it) }
                if (ui.dayBefore) TimeRow("Digest time", hhmm(ui.digestMin), c) { picker = "digest" to ui.digestMin }
            }
            BaytonCard { ToggleRow("Schedule changes", "Alert when a lesson is moved or cancelled", ui.scheduleChanges, c) { vm.setScheduleChanges(it) } }

            if (ui.pupils.size > 1) {
                SectionLabel("Per pupil")
                BaytonCard {
                    ui.pupils.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        ToggleRow(p.name, null, p.enabled, c) { vm.setPupil(p.name, it) }
                    }
                }
            }

            SectionLabel("Quiet hours")
            BaytonCard {
                Text("No reminders or change alerts during these hours.", color = c.inkFaint, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                TimeRow("From", hhmm(ui.quietStart), c) { picker = "quietStart" to ui.quietStart }
                TimeRow("To", hhmm(ui.quietEnd), c) { picker = "quietEnd" to ui.quietEnd }
            }
            GroupHeader("Updates", c, top = 14.dp)
            org.bayton.gmc.update.UpdateSettingsEntry(ctx)
            Spacer(Modifier.height(20.dp))
        }
    }

    picker?.let { (which, current) ->
        TimePickerDialog(current, c, onDismiss = { picker = null }) { min ->
            when (which) {
                "morning" -> vm.setMorningTime(min); "digest" -> vm.setDigestTime(min)
                "quietStart" -> vm.setQuietStart(min); "quietEnd" -> vm.setQuietEnd(min)
            }
            picker = null
        }
    }
}

@Composable private fun GroupHeader(text: String, c: BaytonColors, top: androidx.compose.ui.unit.Dp = 4.dp) {
    Text(text, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = top))
}

@Composable private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, c: BaytonColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, color = c.inkFaint, fontSize = 12.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = c.accent))
    }
}

@Composable private fun TimeRow(label: String, value: String, c: BaytonColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.inkDim, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.accent2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun Segmented(options: List<String>, selected: Int, c: BaytonColors, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().clip(RoundedCornerShape(11.dp)).background(c.canvas2).border(1.dp, c.line, RoundedCornerShape(11.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) c.accent else Color.Transparent)
                .selectable(selected = on, role = Role.RadioButton, interactionSource = null, indication = null) { onSelect(i) }.padding(vertical = 9.dp), Alignment.Center) {
                Text(label, color = if (on) Color(0xFF0E0D0C) else c.inkFaint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TimePickerDialog(currentMin: Int, c: BaytonColors, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = currentMin / 60, initialMinute = currentMin % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Set", color = c.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.inkDim) } },
        text = {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                // Default M3 colours ignore the Bayton DS palette; map every slot to the DS tokens
                // in BaytonColors. On-accent glyphs use the app's near-black ink so they stay
                // legible over the DS orange accent.
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.canvas2,
                        clockDialSelectedContentColor = Color(0xFF0E0D0C),
                        clockDialUnselectedContentColor = c.ink,
                        selectorColor = c.accent,
                        containerColor = c.surface,
                        periodSelectorBorderColor = c.line,
                        periodSelectorSelectedContainerColor = c.accentSoft,
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorSelectedContentColor = c.accent,
                        periodSelectorUnselectedContentColor = c.inkDim,
                        timeSelectorSelectedContainerColor = c.accentSoft,
                        timeSelectorUnselectedContainerColor = c.canvas2,
                        timeSelectorSelectedContentColor = c.accent,
                        timeSelectorUnselectedContentColor = c.ink,
                    ),
                )
            }
        },
        containerColor = c.surface,
    )
}
