package ir.fileo.ui.send

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ir.fileo.FileoApp
import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.discovery.DiscoveryEngine
import ir.fileo.core.discovery.DiscoveryState
import ir.fileo.core.transfer.OutgoingFile
import ir.fileo.core.transfer.TransferEngine
import ir.fileo.core.transfer.TransferSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class SendViewModel(
    private val discovery: DiscoveryEngine,
    private val transfer: TransferEngine,
) : ViewModel() {

    val devices = discovery.devices
    val discoveryState = discovery.state

    private val _pickedUris = MutableStateFlow<List<Uri>>(emptyList())
    val pickedUris: StateFlow<List<Uri>> = _pickedUris.asStateFlow()

    private val _session = MutableStateFlow<TransferSession?>(null)
    val session: StateFlow<TransferSession?> = _session.asStateFlow()

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun onFilesPicked(uris: List<Uri>) {
        _pickedUris.value = uris
    }

    /** ساخت فایل‌های خروجی از URIها و شروع پیشنهاد به دستگاه انتخابی */
    fun offerTo(device: DiscoveredDevice, resolver: ContentResolver): TransferSession? {
        val uris = _pickedUris.value
        if (uris.isEmpty()) return null

        val files = uris.mapIndexed { index, uri ->
            val name = queryDisplayName(resolver, uri) ?: "file-${index + 1}"
            val size = querySize(resolver, uri) ?: 0L
            OutgoingFile(
                id = "f$index",
                name = name,
                size = size,
                mimeType = resolver.getType(uri) ?: "application/octet-stream",
            ) {
                resolver.openInputStream(uri) ?: throw IOException("cannot open $name")
            }
        }

        val session = transfer.offerTo(device, files)
        _session.value = session
        return session
    }

    fun cancelSession() {
        _session.value?.let { transfer.cancel(it.transferId) }
    }

    fun dismissSession() {
        _session.value = null
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as FileoApp).container
                SendViewModel(container.discoveryEngine, container.transferEngine)
            }
        }
    }
}
