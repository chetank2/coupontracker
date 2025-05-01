package com.example.coupontracker.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.coupontracker.ui.screen.ApiTestScreen
import com.example.coupontracker.ui.screen.BatchScannerScreen
import com.example.coupontracker.ui.screen.HomeScreen
import com.example.coupontracker.ui.screen.ManualEntryScreen
import com.example.coupontracker.ui.screen.OnboardingScreen
import com.example.coupontracker.ui.screen.QRScannerScreen
import com.example.coupontracker.ui.screen.ScannerScreen
import com.example.coupontracker.ui.screen.SettingsScreen
import com.example.coupontracker.ui.screen.TesseractTrainingScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object AddCoupon : Screen("add_coupon")
    object CouponDetail : Screen("coupon_detail/{couponId}") {
        fun createRoute(couponId: Long) = "coupon_detail/$couponId"
    }
    object Scanner : Screen("scanner")
    object BatchScanner : Screen("batch_scanner")
    object QRScanner : Screen("qr_scanner")
    object ManualEntry : Screen("manual_entry")
    object ManualEntryWithCode : Screen("manual_entry/{code}") {
        fun createRoute(code: String) = "manual_entry/$code"
    }
    object ApiTest : Screen("api_test")
    object Settings : Screen("settings")
    object TesseractTraining : Screen("tesseract_training")
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

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(navController = navController)
        }

        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }

        composable(Screen.AddCoupon.route) {
            // AddCouponScreen(navController = navController)
            // Temporarily redirect to Home until AddCouponScreen is implemented
            HomeScreen(navController = navController)
        }

        composable(
            route = Screen.CouponDetail.route,
            arguments = listOf(
                navArgument("couponId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            // We retrieve the argument but don't use it until CouponDetailScreen is implemented
            // This is just to ensure type safety with the route format
            backStackEntry.arguments?.getLong("couponId")
            // Temporarily redirect to Home until CouponDetailScreen is implemented
            HomeScreen(navController = navController)
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(navController = navController)
        }

        composable(Screen.ApiTest.route) {
            ApiTestScreen(navController = navController)
        }

        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(Screen.TesseractTraining.route) {
            TesseractTrainingScreen(navController = navController)
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
    }
}