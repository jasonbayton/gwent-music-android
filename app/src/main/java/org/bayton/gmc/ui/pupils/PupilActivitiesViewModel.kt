package org.bayton.gmc.ui.pupils

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import javax.inject.Inject

/** A single enrolment or application, resolved and ready to render. */
data class PupilActivity(
    val id: String,
    val ref: String,            // "DEMO-BOOKING-001"; blank when progressed
    val progressed: Boolean,    // carried over from a prior year
    val activity: String,       // "Brass"
    val venue: String,          // "Example Secondary School"
    val teacher: String?,       // resolved teacher name, or null
    val statusLabel: String,    // "Accepted", "Offered", ...
    val accepted: Boolean,
    val startDate: String?,     // raw ISO
    val academicYear: Int,      // e.g. 2025 => 2025/26
    val hireRequired: Boolean,
)

data class PupilActivitiesUi(
    val applications: List<PupilActivity> = emptyList(),   // pending, not yet enrolled
    val byYear: Map<Int, List<PupilActivity>> = emptyMap(), // enrolments/closed, grouped by academic year
    val years: List<Int> = emptyList(),                     // descending
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PupilActivitiesViewModel @Inject constructor(
    private val repo: GwentRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    private val pupilId: String = handle["pupilId"] ?: ""
    val pupilName: String = handle.get<String>("name")?.trim().orEmpty()

    private val _ui = MutableStateFlow(PupilActivitiesUi())
    val ui = _ui.asStateFlow()
    init { load() }

    fun load() = viewModelScope.launch {
        _ui.value = PupilActivitiesUi(loading = true)
        _ui.value = runCatching { repo.pupilActivities(pupilId) }.fold(
            { all ->
                // A "current application" is a live, not-yet-enrolled status; everything else is history.
                val pending = setOf("Offered", "Pending", "Waiting list", "Invited")
                val applications = all.filter { !it.accepted && it.statusLabel in pending }
                val enrolments = all - applications.toSet()
                val byYear = enrolments.groupBy { it.academicYear }.toSortedMap(compareByDescending { it })
                PupilActivitiesUi(
                    applications = applications,
                    byYear = byYear,
                    years = byYear.keys.toList(),
                    loading = false,
                )
            },
            { PupilActivitiesUi(loading = false, error = it.message ?: "Could not load activities") },
        )
    }
}
