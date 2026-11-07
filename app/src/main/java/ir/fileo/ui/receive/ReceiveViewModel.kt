package ir.fileo.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ir.fileo.FileoApp
import ir.fileo.core.transfer.TransferEngine
import ir.fileo.core.transfer.TransferSession
import ir.fileo.core.transfer.TransferState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReceiveViewModel(private val engine: TransferEngine) : ViewModel() {

    val sessions = engine.sessions

    /** پیشنهاد در انتظار تصمیم کاربر (حداکثر یکی — سرور یک سشن فعال دارد) */
    val incomingOffer: StateFlow<TransferSession?> = engine.sessions
        .map { list ->
            list.firstOrNull {
                it.direction == TransferSession.Direction.RECEIVE &&
                    it.state.value is TransferState.IncomingOffer
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun accept(transferId: String) = engine.acceptIncoming(transferId)

    fun reject(transferId: String) = engine.rejectIncoming(transferId)

    fun cancel(transferId: String) = engine.cancel(transferId)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as FileoApp).container
                ReceiveViewModel(container.transferEngine)
            }
        }
    }
}
