package ir.fileo.ui.send

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.fileo.R
import ir.fileo.ui.common.ComingSoonScreen

@Composable
fun SendScreen(onBack: () -> Unit) {
    ComingSoonScreen(
        title = stringResource(R.string.send_title),
        message = stringResource(R.string.send_soon_desc),
        icon = Icons.Outlined.Devices,
        onBack = onBack,
    )
}
