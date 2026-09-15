package org.bayton.gmc.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bayton.gmc.data.GwentRepository
import org.bayton.gmc.data.model.Debtor
import org.bayton.gmc.data.model.WalletItem
import javax.inject.Inject

data class BillingUi(
    val debtor: Debtor? = null,
    val wallet: WalletItem? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val balance: Double? get() = wallet?.outstandingDebt ?: debtor?.outstandingBalance
    val isEmpty: Boolean get() = debtor == null && wallet == null
}

@HiltViewModel
class BillingViewModel @Inject constructor(private val repo: GwentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(BillingUi())
    val ui = _ui.asStateFlow()
    init { load() }
    fun load() = viewModelScope.launch {
        _ui.value = BillingUi(loading = true)
        _ui.value = runCatching {
            BillingUi(debtor = repo.debtor(), wallet = repo.wallet().firstOrNull(), loading = false)
        }.getOrElse { BillingUi(loading = false, error = it.message ?: "Could not load billing") }
    }
}
