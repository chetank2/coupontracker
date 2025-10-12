package com.example.coupontracker.ui.screen

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.R
import com.example.coupontracker.ui.components.DataSafetyDialog
import com.example.coupontracker.ui.components.PrimaryButton
import com.example.coupontracker.ui.components.TextBrandButton
import com.example.coupontracker.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandSpacing

@Composable
fun OnboardingScreen(
    navController: NavController
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(0) }
    val pages = remember { OnboardingPages.getPages() }
    var showDataSafety by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf(Screen.Home.route) }

    if (showDataSafety) {
        DataSafetyDialog(onDismiss = { showDataSafety = false })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                if (currentPage < pages.size - 1) {
                    TextBrandButton(
                        text = stringResource(id = R.string.onboarding_skip),
                        onClick = { completeOnboarding(context, navController) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            // Page content with animation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Show only the current page
                OnboardingPage(page = pages[currentPage])

                when (currentPage) {
                    1 -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showDataSafety = true }) {
                            Text(stringResource(id = R.string.onboarding_privacy_cta))
                        }
                    }
                    2 -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.onboarding_model_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    pendingDestination = Screen.Settings.route
                                    completeOnboarding(context, navController, Screen.Settings.route)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(id = R.string.onboarding_download_model))
                            }

                            TextButton(
                                onClick = {
                                    if (currentPage < pages.lastIndex) currentPage++
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(stringResource(id = R.string.onboarding_maybe_later))
                            }
                        }
                    }
                    3 -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    completeOnboarding(context, navController, Screen.SmartCamera.route)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(id = R.string.onboarding_scan_camera))
                            }

                            OutlinedButton(
                                onClick = {
                                    completeOnboarding(context, navController, Screen.UnifiedUpload.route)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(id = R.string.onboarding_upload_screenshot))
                            }

                            OutlinedButton(
                                onClick = {
                                    val destination = pendingDestination
                                    completeOnboarding(context, navController, destination)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (pendingDestination == Screen.Settings.route) {
                                        stringResource(id = R.string.onboarding_open_settings)
                                    } else {
                                        stringResource(id = R.string.onboarding_enter_app)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Indicators
            Row(
                modifier = Modifier
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (currentPage == index) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            // Navigation buttons
            if (currentPage < pages.size - 1) {
                PrimaryButton(
                    text = stringResource(id = R.string.onboarding_next),
                    onClick = {
                        if (currentPage < pages.size - 1) {
                            currentPage++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun OnboardingPage(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Image
        Image(
            painter = painterResource(id = page.imageResId),
            contentDescription = null,
            modifier = Modifier
                .size(280.dp)
                .padding(bottom = 32.dp)
        )

        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (page.bullets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(0.95f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                page.bullets.forEach { bullet ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun completeOnboarding(
    context: Context,
    navController: NavController,
    destinationRoute: String = Screen.Home.route
) {
    // Save that onboarding is completed
    val sharedPreferences = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit().putBoolean("onboarding_completed", true).apply()

    // Navigate to home screen
    navController.navigate(destinationRoute) {
        popUpTo(Screen.Onboarding.route) { inclusive = true }
    }
}

data class OnboardingPage(
    val title: String,
    val description: String,
    val imageResId: Int,
    val bullets: List<String> = emptyList()
)

object OnboardingPages {
    fun getPages(): List<OnboardingPage> {
        return listOf(
            OnboardingPage(
                title = "Your private coupon vault",
                description = "Collect every coupon in one encrypted place and stay ahead of expiry dates.",
                imageResId = R.drawable.onboarding_welcome,
                bullets = listOf(
                    "Scan screenshots, images, or shared text in seconds",
                    "We auto-extract code, store, and expiry for you",
                    "Set reminders so you never miss savings"
                )
            ),
            OnboardingPage(
                title = "Private by design",
                description = "Everything runs on this device. No cloud upload, no external AI calls.",
                imageResId = R.drawable.onboarding_share,
                bullets = listOf(
                    "OCR + LLM stay offline – screenshots never leave your phone",
                    "Encrypted backups use Android Keystore",
                    "You choose when to export or clear data"
                )
            ),
            OnboardingPage(
                title = "Faster with the on-device model",
                description = "Download the Qwen2.5 reader once to unlock instant, private extraction.",
                imageResId = R.drawable.onboarding_scan,
                bullets = listOf(
                    "~940 MB download, runs fully offline",
                    "Improves accuracy on tricky coupon layouts",
                    "Works even without internet once installed"
                )
            ),
            OnboardingPage(
                title = "Add your first coupon",
                description = "Import from gallery, scan live, or share from any shopping app.",
                imageResId = R.drawable.onboarding_expiry,
                bullets = listOf(
                    "Camera captures auto-crop receipts and vouchers",
                    "Gallery import keeps original screenshot safe",
                    "Use Android share sheet when you spot a code elsewhere"
                )
            )
        )
    }
}
