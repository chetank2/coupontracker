package com.example.coupontracker.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
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
import com.example.coupontracker.ui.components.PrimaryButton
import com.example.coupontracker.ui.components.TextBrandButton
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandSpacing

@Composable
fun OnboardingScreen(
    navController: NavController
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(0) }
    val pages = remember { OnboardingPages.getPages() }
    
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
                        text = "Skip",
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
                pages.forEachIndexed { index, page ->
                    AnimatedVisibility(
                        visible = currentPage == index,
                        enter = fadeIn(animationSpec = tween(300)) + 
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                ),
                        exit = fadeOut(animationSpec = tween(300)) + 
                               slideOutHorizontally(
                                   targetOffsetX = { -it },
                                   animationSpec = tween(300)
                               )
                    ) {
                        OnboardingPage(page = page)
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
            PrimaryButton(
                text = if (currentPage == pages.size - 1) "Get Started" else "Next",
                onClick = {
                    if (currentPage < pages.size - 1) {
                        currentPage++
                    } else {
                        completeOnboarding(context, navController)
                    }
                },
                leadingIcon = if (currentPage == pages.size - 1) Icons.Default.Check else Icons.Default.ArrowForward,
                modifier = Modifier.fillMaxWidth()
            )
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
    }
}

private fun completeOnboarding(context: Context, navController: NavController) {
    // Save that onboarding is completed
    val sharedPreferences = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit().putBoolean("onboarding_completed", true).apply()
    
    // Navigate to home screen
    navController.navigate(Screen.Home.route) {
        popUpTo(Screen.Onboarding.route) { inclusive = true }
    }
}

data class OnboardingPage(
    val title: String,
    val description: String,
    val imageResId: Int
)

object OnboardingPages {
    fun getPages(): List<OnboardingPage> {
        return listOf(
            OnboardingPage(
                title = "Welcome to Coupon Tracker",
                description = "The easiest way to manage all your coupons in one place and never miss a discount again.",
                imageResId = R.drawable.onboarding_welcome
            ),
            OnboardingPage(
                title = "Multiple Input Methods",
                description = "Scan coupons with your camera, import from gallery, scan QR codes, or enter details manually.",
                imageResId = R.drawable.onboarding_scan
            ),
            OnboardingPage(
                title = "Never Miss Expiry Dates",
                description = "Get notified before your coupons expire so you never miss out on savings.",
                imageResId = R.drawable.onboarding_expiry
            ),
            OnboardingPage(
                title = "Organize & Share",
                description = "Categorize your coupons and easily share them with friends and family.",
                imageResId = R.drawable.onboarding_share
            )
        )
    }
}
