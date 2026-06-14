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

private const val MINICPM_LICENSE_URL = "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5"
private const val QUESTIONNAIRE_URL = "https://modelbest.feishu.cn/share/base/form/shrcnpV5ZT9EJ6xkmaNKWTN7Bcd"

@Composable
fun LicenseGateScreen(
    onLicenseAccepted: () -> Unit,
    securePreferencesManager: SecurePreferencesManager
) {
    val context = LocalContext.current
    var agreedToTerms by remember { mutableStateOf(false) }
    var completedQuestionnaire by remember { mutableStateOf(false) }
    
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
                text = "MiniCPM license",
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
                    text = "Before downloading the MiniCPM-Llama3-V-2.5 model",
                    style = BrandTypography.TitleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "1. This model is free for commercial use",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "2. You must complete the official questionnaire",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "3. By proceeding, you agree to the model's license terms",
                    style = BrandTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        BrandButton(
            text = "Complete questionnaire",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QUESTIONNAIRE_URL))
                context.startActivity(intent)
            },
            tier = BrandButtonTier.Secondary,
            leadingIcon = Icons.Default.OpenInNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge)
        )

        BrandButton(
            text = "View full license",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MINICPM_LICENSE_URL))
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
                checked = completedQuestionnaire,
                onCheckedChange = { completedQuestionnaire = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I have completed the official questionnaire",
                style = BrandTypography.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

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
                text = "I agree to the MiniCPM license terms",
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
            enabled = agreedToTerms && completedQuestionnaire,
            tier = BrandButtonTier.Primary,
            leadingIcon = Icons.Default.Check,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BrandSpacing.ContentEdge)
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        Text(
            text = "By accepting, you confirm that you have read and understood the MiniCPM license terms and completed the required questionnaire.",
            style = BrandTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = BrandSpacing.ContentEdge)
        )
    }
}
