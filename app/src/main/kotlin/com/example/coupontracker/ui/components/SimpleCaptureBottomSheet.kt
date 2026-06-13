package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
import kotlinx.coroutines.launch

/**
 * A simple bottom sheet that shows the four coupon capture options
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleCaptureBottomSheet(
    onDismiss: () -> Unit,
    onCameraCapture: () -> Unit,
    onBatchScan: () -> Unit,
    onQrScan: () -> Unit,
    onManualEntry: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BrandSpacing.Medium),
            shape = MaterialTheme.shapes.large,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BrandSpacing.CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Add coupon",
                style = BrandTypography.HeadlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))

            BrandButton(
                text = "Camera",
                onClick = {
                    onCameraCapture()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            BrandButton(
                text = "Batch scan",
                onClick = {
                    onBatchScan()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tier = BrandButtonTier.Secondary,
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            BrandButton(
                text = "QR code",
                onClick = {
                    onQrScan()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tier = BrandButtonTier.Secondary,
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            BrandButton(
                text = "Manual entry",
                onClick = {
                    onManualEntry()
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tier = BrandButtonTier.Tertiary,
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }
        }
    }
}
