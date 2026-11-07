package ir.fileo.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.fileo.R
import ir.fileo.core.transfer.TransferSession
import ir.fileo.core.transfer.TransferState
import ir.fileo.core.transfer.isTerminal

/** فرمت خوانا برای بایت‌ها */
fun formatBytes(bytes: Long): String = when {
    bytes >= (1L shl 20) -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= (1L shl 10) -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * کارت وضعیت یک سشن انتقال — هم برای فرستنده و هم گیرنده.
 * لغو فقط برای حالت‌های فعال، بستن فقط برای حالت‌های پایان‌یافته.
 */
@Composable
fun TransferProgressCard(
    session: TransferSession,
    onCancel: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = when {
                        state is TransferState.Completed -> Icons.Outlined.DownloadDone
                        session.direction == TransferSession.Direction.SEND -> Icons.AutoMirrored.Filled.Send
                        else -> Icons.Outlined.Download
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleFor(session, state),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    (state as? TransferState.Transferring)?.let { t ->
                        Text(
                            text = stringResource(R.string.transfer_file_position, t.fileIndex, t.fileCount) +
                                " · " + t.currentFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.isTerminal && onCancel != null) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.transfer_cancel),
                        )
                    }
                }
            }

            when (val s = state) {
                is TransferState.Transferring -> {
                    val fraction = if (s.totalBytes > 0) {
                        (s.bytesDone.toDouble() / s.totalBytes).coerceIn(0.0, 1.0)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${formatBytes(s.bytesDone)} / ${formatBytes(s.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${formatBytes(s.bytesPerSecond)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is TransferState.Completed -> {
                    if (s.savedNames.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.receive_saved_to, s.savedNames.joinToString("، ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onDismiss != null) {
                        androidx.compose.material3.TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.transfer_close))
                        }
                    }
                }
                is TransferState.Failed -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onDismiss != null) {
                        androidx.compose.material3.TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.transfer_close))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun titleFor(session: TransferSession, state: TransferState): String = when (state) {
    TransferState.Offering, TransferState.AwaitingApproval -> stringResource(R.string.transfer_waiting_approval)
    TransferState.IncomingOffer -> stringResource(R.string.transfer_waiting_approval)
    is TransferState.Transferring -> stringResource(R.string.transfer_active)
    is TransferState.Completed -> stringResource(R.string.transfer_completed)
    TransferState.Rejected -> stringResource(R.string.transfer_rejected)
    is TransferState.Failed -> stringResource(R.string.transfer_failed)
    TransferState.Cancelled -> stringResource(R.string.transfer_cancelled)
}.let { title ->
    // نام دستگاه مقابل در عنوان
    "$title · ${session.peerName}"
}
