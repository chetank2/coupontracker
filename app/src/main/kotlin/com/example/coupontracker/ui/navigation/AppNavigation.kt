package com.example.coupontracker.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.ui.screen.AnalyticsScreen
import com.example.coupontracker.ui.screen.ApiTestScreen
import com.example.coupontracker.ui.screen.BatchScannerScreen
import com.example.coupontracker.ui.screen.CouponDetailScreen
import com.example.coupontracker.ui.screen.CouponFormScreen
import com.example.coupontracker.ui.screen.ExtractionDashboardScreen
import com.example.coupontracker.ui.screen.HomeScreen
import com.example.coupontracker.ui.screen.ManualEntryScreen
import com.example.coupontracker.ui.screen.OnboardingScreen
import com.example.coupontracker.ui.screen.PrivacyPolicyScreen
import com.example.coupontracker.ui.screen.QRScannerScreen
import com.example.coupontracker.ui.screen.ScannerScreen
import com.example.coupontracker.ui.screen.SettingsScreen
import com.example.coupontracker.ui.screen.SmartCaptureScreen
import com.example.coupontracker.ui.screen.SmartCameraScreen
import com.example.coupontracker.ui.screen.UnifiedCameraScreen
import com.example.coupontracker.ui.screen.UnifiedUploadScreen
import com.example.coupontracker.ui.viewmodel.ScannerViewModel

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object CouponDetail : Screen("coupon_detail/{couponId}") {
        fun createRoute(couponId: Long) = "coupon_detail/$couponId"
    }
    object CouponEdit : Screen("coupon_edit/{couponId}") {
        fun createRoute(couponId: Long) = "coupon_edit/$couponId"
    }
    object Scanner : Screen("scanner")
    object SmartCapture : Screen("smart_capture")
    object SmartCamera : Screen("smart_camera")
    object BatchScanner : Screen("batch_scanner")
    object QRScanner : Screen("qr_scanner")
    object ManualEntry : Screen("manual_entry")
    object ManualEntryWithCode : Screen("manual_entry/{code}") {
        fun createRoute(code: String) = "manual_entry/$code"
    }
    object ApiTest : Screen("api_test")
    object Settings : Screen("settings")
    object PrivacyPolicy : Screen("privacy_policy")

    object Analytics : Screen("analytics")

    // New screens for simplified add coupon flow
    object UnifiedUpload : Screen("unified_upload")
    object UnifiedCamera : Screen("unified_camera")
    object CouponForm : Screen("coupon_form/{imageUri}?isBatchMode={isBatchMode}") {
        fun createRoute(imageUri: String, isBatchMode: Boolean = false): String {
            return "coupon_form/${android.net.Uri.encode(imageUri)}?isBatchMode=$isBatchMode"
        }
    }
    
    object ExtractionDashboard : Screen("extraction_dashboard")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    // Determine start destination based on onboarding status
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }
    val onboardingCompleted = remember {
        sharedPreferences.getBoolean("onboarding_completed", false)
    }

    val startDestination = remember {
        if (onboardingCompleted) Screen.Home.route else Screen.Onboarding.route
    }

    LaunchedEffect(navController, onboardingCompleted) {
        if (!onboardingCompleted) return@LaunchedEffect

        val pendingRoute = when {
            sharedPreferences.getLong("pending_coupon_id", 0L) > 0L -> {
                val couponId = sharedPreferences.getLong("pending_coupon_id", 0L)
                sharedPreferences.edit().remove("pending_coupon_id").apply()
                Screen.CouponDetail.createRoute(couponId)
            }
            sharedPreferences.getString("shared_image_uris", null) != null -> Screen.BatchScanner.route
            sharedPreferences.getString("shared_image_uri", null) != null -> Screen.Scanner.route
            sharedPreferences.getString("shared_url", null) != null -> Screen.ManualEntry.route
            else -> null
        }

        pendingRoute?.let { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(navController = navController)
        }

        composable(Screen.PrivacyPolicy.route) {
            PrivacyPolicyScreen(navController = navController)
        }

        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }

        composable(
            route = Screen.CouponDetail.route,
            arguments = listOf(
                navArgument("couponId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val couponId = backStackEntry.arguments?.getLong("couponId") ?: 0L
            CouponDetailScreen(
                navController = navController,
                couponId = couponId
            )
        }

        composable(
            route = Screen.CouponEdit.route,
            arguments = listOf(
                navArgument("couponId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val couponId = backStackEntry.arguments?.getLong("couponId") ?: 0L
            CouponFormScreen(
                navController = navController,
                imageUri = null,
                editCouponId = couponId
            )
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(navController = navController)
        }

        composable(Screen.SmartCapture.route) {
            SmartCaptureScreen(navController = navController)
        }

        composable(Screen.SmartCamera.route) {
            SmartCameraScreen(
                onPhotoTaken = { uri ->
                    // Navigate to CouponForm with the captured image
                    navController.navigate(
                        Screen.CouponForm.createRoute(
                            imageUri = uri.toString(),
                            isBatchMode = false
                        )
                    )
                },
                onBackPressed = {
                    navController.navigateUp()
                }
            )
        }

        composable(Screen.ApiTest.route) {
            ApiTestScreen(navController = navController)
        }

        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }



        composable(Screen.Analytics.route) {
            AnalyticsScreen(navController = navController)
        }

        composable(Screen.BatchScanner.route) {
            BatchScannerScreen(navController = navController)
        }

        composable(Screen.QRScanner.route) {
            QRScannerScreen(navController = navController)
        }

        composable(Screen.ManualEntry.route) {
            ManualEntryScreen(navController = navController)
        }

        composable(
            route = Screen.ManualEntryWithCode.route,
            arguments = listOf(
                navArgument("code") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString("code")
            ManualEntryScreen(navController = navController, initialCode = code)
        }

        // New screens for simplified add coupon flow
        composable(Screen.UnifiedUpload.route) {
            UnifiedUploadScreen(navController = navController)
        }

        composable(Screen.UnifiedCamera.route) {
            UnifiedCameraScreen(navController = navController)
        }

        composable(
            route = Screen.CouponForm.route,
            arguments = listOf(
                navArgument("imageUri") { type = NavType.StringType },
                navArgument("isBatchMode") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val imageUri = backStackEntry.arguments?.getString("imageUri")
            val isBatchMode = backStackEntry.arguments?.getBoolean("isBatchMode") ?: false
            val scannerBackStackEntry = remember(backStackEntry) {
                navController.previousBackStackEntry?.takeIf {
                    it.destination.route == Screen.Scanner.route
                }
            }
            val scannerViewModel: ScannerViewModel? = scannerBackStackEntry?.let { hiltViewModel(it) }
            CouponFormScreen(
                navController = navController,
                imageUri = imageUri,
                isBatchMode = isBatchMode,
                scannerViewModel = scannerViewModel
            )
        }
        
        composable(Screen.ExtractionDashboard.route) {
            ExtractionDashboardScreen(navController = navController)
        }
    }
}
