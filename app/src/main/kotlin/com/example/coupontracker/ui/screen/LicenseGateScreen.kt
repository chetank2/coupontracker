package com.example.coupontracker.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // Icon
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Title
        Text(
            text = "MiniCPM Model License",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // License Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Before downloading the MiniCPM-Llama3-V-2.5 model:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "1. This model is free for commercial use",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "2. You must complete the official questionnaire",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "3. By proceeding, you agree to the model's license terms",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Questionnaire Button
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QUESTIONNAIRE_URL))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Complete Questionnaire")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // License Link Button
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MINICPM_LICENSE_URL))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Full License")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Checkboxes
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = completedQuestionnaire,
                onCheckedChange = { completedQuestionnaire = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I have completed the official questionnaire",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = agreedToTerms,
                onCheckedChange = { agreedToTerms = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "I agree to the MiniCPM license terms",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Accept Button
        Button(
            onClick = {
                securePreferencesManager.setMiniCpmLicenseAccepted(true)
                onLicenseAccepted()
            },
            enabled = agreedToTerms && completedQuestionnaire,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Accept & Continue")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Disclaimer
        Text(
            text = "By accepting, you confirm that you have read and understood the MiniCPM license terms and completed the required questionnaire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

