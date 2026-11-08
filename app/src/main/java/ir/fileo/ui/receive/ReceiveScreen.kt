package ir.fileo.ui.receive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.fileo.R
import ir.fileo.core.qr.QrCodeGenerator
import ir.fileo.core.transfer.TransferSession
import ir.fileo.service.TransferService
import ir.fileo.ui.transfer.TransferProgressCard
import ir.fileo.ui.transfer.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    val viewModel: ReceiveViewModel = viewModel(factory = ReceiveViewModel.Factory)
    val incomingOffer by viewModel.incomingOffer.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val context = LocalContext.current

    // خروج از صفحه = خاموشی هات‌اسپات (اگر روشن باشد)
    DisposableEffect(Unit) {
        onDispose { viewModel.stopConnection() }
    }

    // درخواست مجوزها قبل از پذیرش — حتی بدون مجوز ادامه می‌دهیم
    // (انتقال کار می‌کند، فقط نوتیفیکیشن پیشرفت ندارد)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    fun runWithPermissions(action: () -> Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(needed.first())
        }
    }

    // ---- مجوزهای هات‌اسپات (اندروید ۱۳+: NEARBY_WIFI_DEVICES | اندروید ۹-۱۲: موقعیت مکانی) ----
    var pendingHotspotAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        val action = pendingHotspotAction
        pendingHotspotAction = null
        action?.invoke() // حتی بدون مجوز هم تلاش می‌کنیم — خطای گویا نمایش داده می‌شود
    }

    fun startHotspotWithPermissions() {
        val needed = when {
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.NEARBY_WIFI_DEVICES
            Build.VERSION.SDK_INT in 29..32 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.ACCESS_FINE_LOCATION
            else -> null
        }
        if (needed == null) {
            viewModel.startHotspot()
        } else {
            pendingHotspotAction = { viewModel.startHotspot() }
            hotspotPermissionLauncher.launch(needed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            incomingOffer?.let { offer ->
                IncomingOfferCard(
                    session = offer,
                    onAccept = {
                        runWithPermissions {
                            viewModel.accept(offer.transferId)
                            TransferService.start(context)
                        }
                    },
                    onReject = { viewModel.reject(offer.transferId) },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // بخش اتصال سریع (QR / هات‌اسپات)
            when {
                connection.qr != null -> ConnectionQrCard(
                    qr = connection.qr!!,
                    onClose = { viewModel.stopConnection() },
                )
                connection.starting -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.receive_hotspot_starting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { viewModel.showPairingQr() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.receive_pairing_qr))
                        }
                        Text(
                            text = stringResource(R.string.receive_pairing_qr_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = { startHotspotWithPermissions() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WifiTethering,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.receive_hotspot_create))
                        }
                        Text(
                            text = stringResource(R.string.receive_hotspot_create_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            connection.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // کارت‌های وضعیت سشن‌های دریافت (فعال/تمام‌شده)
            val receiveSessions = sessions.filter {
                it.direction == TransferSession.Direction.RECEIVE &&
                    it.state.value !is ir.fileo.core.transfer.TransferState.IncomingOffer
            }
            receiveSessions.forEach { session ->
                Spacer(modifier = Modifier.height(12.dp))
                TransferProgressCard(
                    session = session,
                    onCancel = { viewModel.cancel(session.transferId) },
                )
            }

            if (incomingOffer == null && receiveSessions.isEmpty() && connection.qr == null && !connection.starting) {
                EmptyReceiveState(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** کارت نمایش QR اتصال + مشخصات شبکه در صورت وجود هات‌اسپات */
@Composable
private fun ConnectionQrCard(
    qr: ir.fileo.core.qr.QrContent,
    onClose: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (qr.hotspot != null) {
                        stringResource(R.string.receive_hotspot_active)
                    } else {
                        stringResource(R.string.receive_qr_active)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.receive_stop_connection))
                }
            }

            val qrImage = remember(qr) { QrCodeGenerator.generate(qr.encode(), 512) }
            Image(
                bitmap = qrImage,
                contentDescription = stringResource(R.string.receive_qr_alt),
                modifier = Modifier
                    .size(240.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                contentScale = ContentScale.Fit,
            )

            Text(
                text = stringResource(R.string.receive_qr_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            qr.hotspot?.let { hot ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.receive_ssid_label) + ": " + hot.ssid,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.receive_pass_label) + ": " + hot.passphrase,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.receive_hotspot_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (qr.fingerprint.isNotBlank()) {
                Text(
                    text = stringResource(R.string.receive_security_code) + ": " +
                        formatFingerprint(qr.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun formatFingerprint(fingerprint: String): String =
    fingerprint.take(16).chunked(2).joinToString("-").uppercase()

@Composable
private fun IncomingOfferCard(
    session: TransferSession,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.receive_offer_from, session.peerName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.receive_files_count,
                            session.files.size,
                            formatBytes(session.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            session.files.take(3).forEach { file ->
                Text(
                    text = "• ${file.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.files.size > 3) {
                Text(
                    text = stringResource(R.string.receive_more_files, session.files.size - 3),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.receive_accept))
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.receive_reject))
                }
            }
        }
    }
}

@Composable
private fun EmptyReceiveState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCode2,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.receive_waiting_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.receive_waiting_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
