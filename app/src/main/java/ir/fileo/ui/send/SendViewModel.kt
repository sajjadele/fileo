package ir.fileo.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ir.fileo.FileoApp
import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.discovery.DiscoveryEngine
import ir.fileo.core.discovery.DiscoveryState

class SendViewModel(private val engine: DiscoveryEngine) : ViewModel() {

    val devices = engine.devices
    val state = engine.state

    fun startDiscovery() = engine.start()

    fun stopDiscovery() = engine.stop()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FileoApp
                SendViewModel(app.container.discoveryEngine)
            }
        }
    }
}
