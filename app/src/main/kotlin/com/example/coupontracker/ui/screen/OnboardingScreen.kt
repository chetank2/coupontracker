package com.example.coupontracker.ui.screen

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.R
import com.example.coupontracker.ui.components.DataSafetyDialog
import com.example.coupontracker.ui.components.PrimaryButton
import com.example.coupontracker.ui.components.SecondaryButton
import com.example.coupontracker.ui.components.TextBrandButton
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.ModelImportUiState
import com.example.coupontracker.ui.viewmodel.ModelImportViewModel

@Composable
private fun CouponOnboardingLayout(
    totalPages: Int,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    showSkip: Boolean,
    onSkip: () -> Unit,
    content: @Composable (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = BrandSpacing.Large)
            .padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = BrandSpacing.Small)
        ) {
            if (showSkip) {
                TextBrandButton(
                    text = stringResource(id = R.string.onboarding_skip),
                    onClick = onSkip,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentColor = BrandColors.Accent
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = currentPage, label = "onboarding_page") { pageIndex ->
                content(pageIndex)
            }
        }

        PaginationIndicators(
            totalPages = totalPages + 1,
            currentPage = currentPage,
            modifier = Modifier
                .padding(top = BrandSpacing.Medium, bottom = BrandSpacing.Medium)
        )

        if (currentPage < totalPages) {
            PrimaryButton(
                text = stringResource(id = R.string.onboarding_next),
                onClick = { onPageChange(currentPage + 1) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PaginationIndicators(
    totalPages: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    indicatorShape: Shape = MaterialTheme.shapes.small
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(totalPages) { index ->
            val isActive = currentPage == index
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .clip(indicatorShape)
                    .background(
                        if (isActive) BrandColors.Accent else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .let { base ->
                        if (isActive) base.width(18.dp) else base.width(8.dp)
                    }
            )
        }
    }
}

@Composable
private fun IllustrationWithGlow(
    imageRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        val gradient = Brush.radialGradient(
            colors = listOf(
                BrandColors.Accent.copy(alpha = 0.3f),
                Color.Transparent
            ),
            radius = 220f
        )

        Surface(
            modifier = Modifier
                .size(220.dp)
                .clip(MaterialTheme.shapes.extraLarge),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient)
            )
        }

        Image(
            painter = painterResource(id = imageRes),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .size(180.dp)
                        .semantics { this.contentDescription = contentDescription }
        )
    }
}

@Composable
private fun ModelDownloadPage(
    page: OnboardingPage,
    modelState: ModelImportUiState,
    onDownload: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingContentPage(
            page = page,
            illustrationDescription = stringResource(id = R.string.onboarding_model_illustration_desc)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
        ) {
            if (modelState.isImporting) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
                    color = BrandColors.SurfaceElevated
                ) {
                    Column(
                        modifier = Modifier.padding(BrandSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
                    ) {
                        Text(
                            text = stringResource(id = R.string.onboarding_model_downloading),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = modelState.importMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = modelState.importProgress / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(
                                id = R.string.onboarding_model_progress_percent,
                                modelState.importProgress
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            PrimaryButton(
                text = if (modelState.isImporting) {
                    stringResource(id = R.string.onboarding_model_downloading_button)
                } else {
                    stringResource(id = R.string.onboarding_download_model)
                },
                onClick = onDownload,
                enabled = !modelState.isImporting,
                modifier = Modifier.fillMaxWidth()
            )

            TextBrandButton(
                text = stringResource(id = R.string.onboarding_maybe_later),
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GetStartedPage(
    page: OnboardingPage,
    onScan: () -> Unit,
    onUpload: () -> Unit,
    onEnterApp: () -> Unit,
    onOpenSettings: () -> Unit,
    isModelInstalled: Boolean,
    pendingDestination: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        OnboardingContentPage(
            page = page,
            illustrationDescription = stringResource(id = R.string.onboarding_first_coupon_desc)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
        ) {
            PrimaryButton(
                text = stringResource(id = R.string.onboarding_scan_camera),
                onClick = onScan,
                modifier = Modifier.fillMaxWidth()
            )

            SecondaryButton(
                text = stringResource(id = R.string.onboarding_upload_screenshot),
                onClick = onUpload,
                modifier = Modifier.fillMaxWidth()
            )

            TextBrandButton(
                text = if (pendingDestination == Screen.Settings.route && !isModelInstalled) {
                    stringResource(id = R.string.onboarding_open_settings)
                } else {
                    stringResource(id = R.string.onboarding_enter_app)
                },
                onClick = if (pendingDestination == Screen.Settings.route && !isModelInstalled) onOpenSettings else onEnterApp,
                modifier = Modifier.fillMaxWidth(),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OnboardingContentPage(
    page: OnboardingPage,
    illustrationDescription: String,
    footerContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BrandSpacing.Large)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        IllustrationWithGlow(
            imageRes = page.imageResId,
            contentDescription = illustrationDescription
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = page.description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            if (page.bullets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(BrandSpacing.Small))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BrandSpacing.ExtraSmall)
                ) {
                    page.bullets.forEach { bullet ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
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

        footerContent?.let {
            Spacer(modifier = Modifier.height(BrandSpacing.Large))
            it()
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

@Composable
fun OnboardingScreen(
    navController: NavController,
    modelImportViewModel: ModelImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val pages = remember { OnboardingPages.getPages() }
    var currentPage by rememberSaveable { mutableStateOf(0) }
    var showDataSafety by remember { mutableStateOf(false) }
    var pendingDestination by rememberSaveable { mutableStateOf(Screen.Home.route) }
    val modelState by modelImportViewModel.uiState.collectAsState()
    val totalPages = pages.lastIndex

    if (showDataSafety) {
        DataSafetyDialog(
            onDismiss = { showDataSafety = false },
            onLearnMore = {
                showDataSafety = false
                navController.navigate(Screen.PrivacyPolicy.route)
            }
        )
    }

    CouponOnboardingLayout(
        totalPages = totalPages,
        currentPage = currentPage,
        onPageChange = { currentPage = it },
        showSkip = currentPage < totalPages,
        onSkip = { completeOnboarding(context, navController) }
    ) { pageIndex ->
        val page = pages[pageIndex]
        when (pageIndex) {
            0 -> OnboardingContentPage(
                page = page,
                illustrationDescription = stringResource(id = R.string.onboarding_vault_illustration_desc)
            )
            1 -> {
                val privacyCtaLabel = stringResource(id = R.string.onboarding_privacy_cta)
                OnboardingContentPage(
                    page = page,
                    illustrationDescription = stringResource(id = R.string.onboarding_privacy_illustration_desc),
                    footerContent = {
                        TextButton(
                            onClick = { showDataSafety = true },
                            modifier = Modifier.semantics { contentDescription = privacyCtaLabel }
                        ) {
                            Text(
                                text = privacyCtaLabel,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                )
            }
            2 -> {
                ModelDownloadPage(
                    page = page,
                    modelState = modelState,
                    onDownload = {
                        pendingDestination = Screen.Settings.route
                        modelImportViewModel.downloadModel()
                    },
                    onSkip = {
                        if (currentPage < totalPages) currentPage++
                    }
                )
            }
            3 -> {
                GetStartedPage(
                    page = page,
                    onScan = {
                        completeOnboarding(context, navController, Screen.SmartCamera.route)
                    },
                    onUpload = {
                        completeOnboarding(context, navController, Screen.UnifiedUpload.route)
                    },
                    onEnterApp = {
                        val destination = pendingDestination
                        completeOnboarding(context, navController, destination)
                    },
                    onOpenSettings = {
                        pendingDestination = Screen.Settings.route
                        completeOnboarding(context, navController, Screen.Settings.route)
                    },
                    isModelInstalled = modelState.isModelInstalled,
                    pendingDestination = pendingDestination
                )
            }
        }
    }
}
