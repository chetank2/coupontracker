package com.example.coupontracker.ui.modelsettings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
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
import com.example.coupontracker.data.preferences.SecurePreferencesManager

private const val QWEN_MODEL_PAGE_URL = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF"
private const val GEMMA_MODEL_PAGE_URL = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview"

data class ModelLicenseGateConfig(
    val title: String,
    val checklistTitle: String,
    val modelPageUrl: String,
    val checklistItems: List<String>,
    val alreadyAccepted: (SecurePreferencesManager) -> Boolean,
    val markAccepted: (SecurePreferencesManager) -> Unit
) {
    companion object {
        val Qwen = ModelLicenseGateConfig(
            title = "Offline reader license",
            checklistTitle = "Before downloading the Qwen2.5 offline reader",
            modelPageUrl = QWEN_MODEL_PAGE_URL,
            checklistItems = listOf(
                "Review the model provider page",
                "The download is about 940 MB",
                "Coupon cleaning runs on this device"
            ),
            alreadyAccepted = { it.isMiniCpmLicenseAccepted() },
            markAccepted = { it.setMiniCpmLicenseAccepted(true) }
        )

        val GemmaVision = ModelLicenseGateConfig(
            title = "Gemma Vision access",
            checklistTitle = "Before downloading Gemma Vision",
            modelPageUrl = GEMMA_MODEL_PAGE_URL,
            checklistItems = listOf(
                "Open ${GEMMA_MODEL_PAGE_URL} and accept Google Gemma terms",
                "The download is about 3.1 GB",
                "Vision verification runs only when OCR confidence is low",
                "If Hugging Face blocks download, import the downloaded .task file manually"
            ),
            alreadyAccepted = { it.isGemmaVisionLicenseAccepted() },
            markAccepted = { it.setGemmaVisionLicenseAccepted(true) }
        )
    }
}

@Composable
fun LicenseGateScreen(
    onLicenseAccepted: () -> Unit,
    securePreferencesManager: SecurePreferencesManager,
    config: ModelLicenseGateConfig = ModelLicenseGateConfig.Qwen
) {
    val context = LocalContext.current
    var agreedToTerms by remember { mutableStateOf(false) }

    // Check if already accepted
    LaunchedEffect(Unit) {
        if (config.alreadyAccepted(securePreferencesManager)) {
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
                text = config.title,
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
                    text = config.checklistTitle,
                    style = BrandTypography.TitleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                config.checklistItems.forEachIndexed { index, item ->
                    Text(
                        text = "${index + 1}. $item",
                        style = BrandTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        BrandButton(
            text = "View model page",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.modelPageUrl))
                context.startActivity(intent)
            },
            tier = BrandButtonTier.Secondary,
            leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
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
                config.markAccepted(securePreferencesManager)
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
