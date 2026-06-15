package com.example.coupontracker.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.components.BrandButton
import com.example.coupontracker.ui.components.BrandButtonTier
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.util.SecurePreferencesManager

private const val MODEL_LICENSE_URL = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF"

@Composable
fun LicenseGateScreen(
    onLicenseAccepted: () -> Unit,
    securePreferencesManager: SecurePreferencesManager
) {
    val context = LocalContext.current
    var agreedToTerms by remember { mutableStateOf(false) }
    
    // Check if already accepted
    LaunchedEffect(Unit) {
        if (securePreferencesManager.isMiniCpmLicenseAccepted()) {
            onLicenseAccepted()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandTopBar(title = "Model access")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge, vertical = BrandSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Offline reader license",
                style = BrandTypography.DisplayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            Text(
                text = "Complete the model provider steps once before downloading offline scanning files.",
                style = BrandTypography.BodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge),
            shape = BrandShapes.Large,
            border = BorderStroke(BrandSpacing.Hairline, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(BrandSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
            ) {
                Text(
                    text = "Before downloading the Qwen2.5 offline reader",
                    style = BrandTypography.TitleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "1. Review the model provider page",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "2. The download is about 940 MB",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "3. Coupon cleaning runs on this device",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        BrandButton(
            text = "View model page",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_LICENSE_URL))
                context.startActivity(intent)
            },
            tier = BrandButtonTier.Secondary,
            leadingIcon = Icons.Default.OpenInNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge)
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = agreedToTerms,
                onCheckedChange = { agreedToTerms = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I agree to the model provider terms",
                style = BrandTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        BrandButton(
            text = "Accept and continue",
            onClick = {
                securePreferencesManager.setMiniCpmLicenseAccepted(true)
                onLicenseAccepted()
            },
            enabled = agreedToTerms,
            tier = BrandButtonTier.Primary,
            leadingIcon = Icons.Default.Check,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge)
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        Text(
            text = "By accepting, you confirm that you have reviewed the model provider terms.",
            style = BrandTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = BrandSpacing.ContentEdge)
        )
    }
}
