package com.example.coupontracker.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.ui.components.BrandTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    val context = LocalContext.current
    val policyText by produceState(initialValue = "") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
            }.getOrElse { "Privacy policy is unavailable in this build." }
        }
    }
    val externalUrl = remember { BuildConfig.PRIVACY_POLICY_URL.trim() }
    var openExternal by remember { mutableStateOf(false) }

    LaunchedEffect(openExternal, externalUrl) {
        if (openExternal && externalUrl.isNotBlank()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
            openExternal = false
        }
    }

    Scaffold(
        topBar = {
            BrandTopBar(
                title = "Privacy policy",
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (externalUrl.isNotBlank()) {
                        TextButton(onClick = { openExternal = true }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Text(text = "Open", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = policyText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
