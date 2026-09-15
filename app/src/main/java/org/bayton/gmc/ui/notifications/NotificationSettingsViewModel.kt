package org.bayton.gmc.ui.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.notify.NotificationPrefs
import org.bayton.gmc.notify.NotificationScheduler
import javax.inject.Inject

data class PupilToggle(val name: String, val enabled: Boolean)

data class NotifSettingsUi(
    val messages: Boolean = true,
    val morning: Boolean = true,
    val morningMin: Int = 7 * 60 + 30,
    val dayBefore: Boolean = true,
    val digestMin: Int = 18 * 60,
    val scheduleChanges: Boolean = true,
    val quietStart: Int = 21 * 60,
    val quietEnd: Int = 7 * 60,
    val pupils: List<PupilToggle> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val repo: GwentRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val prefs = NotificationPrefs(appContext)
    private val _ui = MutableStateFlow(NotifSettingsUi())
    val ui = _ui.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        val names = runCatching { repo.pupils() }.getOrDefault(emptyList())
            .mapNotNull { it.firstName?.trim()?.ifBlank { null } }.distinct()
        _ui.value = snapshot(names.map { PupilToggle(it, prefs.pupilEnabled(it)) })
    }

    private fun snapshot(pupils: List<PupilToggle>) = NotifSettingsUi(
        messages = prefs.messages, morning = prefs.morning, morningMin = prefs.morningMinutes,
        dayBefore = prefs.dayBefore, digestMin = prefs.digestMinutes, scheduleChanges = prefs.scheduleChanges,
        quietStart = prefs.quietStartMinutes, quietEnd = prefs.quietEndMinutes, pupils = pupils, loading = false,
    )

    /** Any change re-plans reminders so the new times/toggles take effect immediately. */
    private fun apply(mutate: () -> Unit) {
        mutate()
        _ui.value = snapshot(_ui.value.pupils)
        NotificationScheduler.syncNow(appContext)
    }

    fun setMessages(v: Boolean) = apply { prefs.messages = v }
    fun setMorning(v: Boolean) = apply { prefs.morning = v }
    fun setMorningTime(min: Int) = apply { prefs.morningMinutes = min }
    fun setDayBefore(v: Boolean) = apply { prefs.dayBefore = v }
    fun setDigestTime(min: Int) = apply { prefs.digestMinutes = min }
    fun setScheduleChanges(v: Boolean) = apply { prefs.scheduleChanges = v }
    fun setQuietStart(min: Int) = apply { prefs.quietStartMinutes = min }
    fun setQuietEnd(min: Int) = apply { prefs.quietEndMinutes = min }
    fun setPupil(name: String, enabled: Boolean) = apply {
        prefs.setPupilEnabled(name, enabled)
        _ui.value = _ui.value.copy(pupils = _ui.value.pupils.map { if (it.name == name) it.copy(enabled = enabled) else it })
    }
}
