package com.example.coupontracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.coupontracker.ui.screen.ApiTestScreen
import com.example.coupontracker.ui.screen.HomeScreen
import com.example.coupontracker.ui.screen.ScannerScreen
import com.example.coupontracker.ui.screen.SettingsScreen
import com.example.coupontracker.ui.screen.TesseractTrainingScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object AddCoupon : Screen("add_coupon")
    object CouponDetail : Screen("coupon_detail/{couponId}") {
        fun createRoute(couponId: Long) = "coupon_detail/$couponId"
    }
    object Scanner : Screen("scanner")
    object ApiTest : Screen("api_test")
    object Settings : Screen("settings")
    object TesseractTraining : Screen("tesseract_training")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
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
    }
}