package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.coupontracker.R

/**
 * Reusable dialog that explains how the app handles privacy and on-device processing.
 */
@Composable
fun DataSafetyDialog(
    onDismiss: () -> Unit,
    onLearnMore: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_close))
            }
        },
        dismissButton = {
            if (onLearnMore != null) {
                TextButton(onClick = onLearnMore) {
                    Text(stringResource(id = R.string.data_safety_privacy_policy))
                }
            }
        },
        title = {
            Text(
                text = stringResource(id = R.string.data_safety_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.data_safety_intro),
                    style = MaterialTheme.typography.bodyMedium
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DialogBulletPoint(
                        title = stringResource(id = R.string.data_safety_bullet_ai_title),
                        description = stringResource(id = R.string.data_safety_bullet_ai_desc)
                    )
                    DialogBulletPoint(
                        title = stringResource(id = R.string.data_safety_bullet_backup_title),
                        description = stringResource(id = R.string.data_safety_bullet_backup_desc)
                    )
                    DialogBulletPoint(
                        title = stringResource(id = R.string.data_safety_bullet_tracking_title),
                        description = stringResource(id = R.string.data_safety_bullet_tracking_desc)
                    )
                }

                Text(
                    text = stringResource(id = R.string.data_safety_backup_hint),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(id = R.string.data_safety_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun DialogBulletPoint(
    title: String,
    description: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

