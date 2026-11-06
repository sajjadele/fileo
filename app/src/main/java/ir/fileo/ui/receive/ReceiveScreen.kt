package ir.fileo.ui.receive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.fileo.R
import ir.fileo.ui.common.ComingSoonScreen

@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    ComingSoonScreen(
        title = stringResource(R.string.receive_title),
        message = stringResource(R.string.receive_soon_desc),
        icon = Icons.Outlined.QrCode2,
        onBack = onBack,
    )
}
