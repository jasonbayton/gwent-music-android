package org.bayton.gmc.ui.pupils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.model.RelatedPupil
import javax.inject.Inject

data class PupilsUi(val items: List<RelatedPupil> = emptyList(), val loading: Boolean = true, val error: String? = null)

@HiltViewModel
class PupilsViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(PupilsUi())
    val ui = _ui.asStateFlow()
    init { load() }
    fun load() = viewModelScope.launch {
        _ui.value = PupilsUi(loading = true)
        _ui.value = runCatching { repo.pupils() }
            .fold({ PupilsUi(items = it, loading = false) }, { PupilsUi(loading = false, error = it.message ?: "Could not load pupils") })
    }
}
