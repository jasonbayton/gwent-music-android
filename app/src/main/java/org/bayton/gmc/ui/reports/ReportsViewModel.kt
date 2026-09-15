package org.bayton.gmc.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.model.ReportItem
import javax.inject.Inject

data class ReportsUi(val items: List<ReportItem> = emptyList(), val loading: Boolean = true, val error: String? = null)

@HiltViewModel
class ReportsViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ReportsUi())
    val ui = _ui.asStateFlow()
    init { load() }
    fun load() = viewModelScope.launch {
        _ui.value = ReportsUi(loading = true)
        _ui.value = runCatching { repo.reports() }
            .fold({ ReportsUi(items = it, loading = false) }, { ReportsUi(loading = false, error = it.message ?: "Could not load reports") })
    }
}
