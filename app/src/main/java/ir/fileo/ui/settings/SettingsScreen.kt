package ir.fileo.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.fileo.R

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val deviceName = remember { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_device_name)) },
            supportingContent = { Text(deviceName) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                )
            },
        )
    }
}
