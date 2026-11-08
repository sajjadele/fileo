package ir.fileo.ui.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.fileo.R
import ir.fileo.core.discovery.DiscoveredDevice
import ir.fileo.core.discovery.DiscoveryState
import ir.fileo.service.TransferService
import ir.fileo.ui.connect.QrScannerScreen
import ir.fileo.ui.transfer.TransferProgressCard
import ir.fileo.ui.transfer.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(onBack: () -> Unit) {
    val viewModel: SendViewModel = viewModel(factory = SendViewModel.Factory)
    val context = LocalContext.current

    var showScanner by remember { mutableStateOf(false) }
    if (showScanner) {
        QrScannerScreen(
            onResult = { raw ->
                showScanner = false
                viewModel.onQrScanned(raw)
            },
            onClose = { showScanner = false },
        )
        return
    }

    val devices by viewModel.devices.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val pickedUris by viewModel.pickedUris.collectAsState()
    val session by viewModel.session.collectAsState()
    val connectionAttempt by viewModel.connectionAttempt.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // کشف دستگاه‌ها فقط تا وقتی این صفحه باز است
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesPicked(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.send_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val activeSession = session?.takeIf { !it.isTerminalState() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            if (activeSession != null) {
                // انتقال در جریان است — فقط کارت پیشرفت
                TransferProgressCard(
                    session = activeSession,
                    onCancel = { viewModel.cancelSession() },
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.send_transfer_in_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                session?.let {
                    TransferProgressCard(
                        session = it,
                        onDismiss = { viewModel.dismissSession() },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // اتصال از طریق QR در جریان است؟
                connectionAttempt?.let { attempt ->
                    ConnectionAttemptCard(
                        attempt = attempt,
                        onCancel = { viewModel.cancelConnectionAttempt() },
                        onRetry = { viewModel.retryConnectionAttempt() },
                        onCopy = { value ->
                            copyToClipboard(context, value)
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.connect_copied)) }
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                FilePickerSection(
                    pickedCount = pickedUris.size,
                    pickedBytes = pickedUris.sumOf { it.byteSize(context) },
                    onPick = { pickerLauncher.launch(arrayOf("*/*")) },
                    onClear = { viewModel.onFilesPicked(emptyList()) },
                    onScanQr = { showScanner = true },
                )

                DeviceListSection(
                    devices = devices,
                    scanning = discoveryState is DiscoveryState.Scanning,
                    onDeviceTapped = { device ->
                        viewModel.offerTo(device)
                        TransferService.start(context)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("fileo", value))
    }
}

private fun android.net.Uri.byteSize(context: Context): Long = runCatching {
    context.contentResolver.openAssetFileDescriptor(this, "r")?.use { it.length } ?: 0L
}.getOrDefault(0L)

@Composable
private fun ConnectionAttemptCard(
    attempt: ir.fileo.ui.send.ConnectionAttempt,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    val qr = attempt.qr
    val failed = attempt.phase == ConnectPhase.FAILED

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (failed) {
                    Icon(
                        imageVector = Icons.Outlined.WifiOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (attempt.phase) {
                            ConnectPhase.CONNECTING_NETWORK ->
                                stringResource(R.string.connect_phase_network)
                            ConnectPhase.SEARCHING ->
                                stringResource(R.string.connect_phase_searching, qr.deviceName)
                            ConnectPhase.FAILED ->
                                stringResource(R.string.connect_failed_title)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (failed) {
                        Text(
                            text = stringResource(R.string.connect_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.connect_cancel),
                    )
                }
            }

            if (failed) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.connect_retry))
                }
            }

            // اگر QR شامل هات‌اسپات است — مسیر اتصال دستی هم در دسترس باشد
            val ssid = qr.hotspot?.ssid
            val passphrase = qr.hotspot?.passphrase
            if (ssid != null && passphrase != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.connect_manual_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.connect_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CredentialRow(label = "SSID", value = ssid, onCopy = onCopy)
                CredentialRow(label = stringResource(R.string.receive_pass_label), value = passphrase, onCopy = onCopy)
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    },
                ) {
                    Text(stringResource(R.string.connect_open_settings))
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(value) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.connect_copy))
        }
    }
}

@Composable
private fun FilePickerSection(
    pickedCount: Int,
    pickedBytes: Long,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onScanQr: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPick) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.send_pick_files))
        }
        Column {
            if (pickedCount > 0) {
                Text(
                    text = stringResource(R.string.send_picked_count, pickedCount) +
                        " · " + formatBytes(pickedBytes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.send_clear_files))
                    }
                    TextButton(onClick = onScanQr, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.send_scan_qr))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.send_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceListSection(
    devices: List<DiscoveredDevice>,
    scanning: Boolean,
    onDeviceTapped: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = if (scanning) stringResource(R.string.send_scanning) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (devices.isNotEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.send_found_count, devices.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.send_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.send_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceTapped(device) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle = buildString {
                    if (device.model.isNotBlank()) append(device.model)
                    if (device.ip.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(device.ip)
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
