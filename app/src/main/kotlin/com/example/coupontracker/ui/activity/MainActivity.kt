package com.example.coupontracker.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.coupontracker.ui.navigation.AppNavigation
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.CouponTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // Reference to the NavController for navigation from non-composable functions
    private var navControllerRef: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate started")

        setContent {
            CouponTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    // Store the NavController for use in handleSharedContent
                    navControllerRef = navController

                    AppNavigation(navController = navController)
                }
            }
        }

        // Handle shared content if this activity was started from a share intent
        handleSharedContent(intent)

        Log.d(TAG, "MainActivity onCreate completed successfully")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedContent(intent)
    }

    private fun handleSharedContent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    handleSharedImage(intent)
                } else if (intent.type == "text/plain") {
                    handleSharedText(intent)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    handleMultipleSharedImages(intent)
                }
            }
        }
    }

    private fun handleSharedImage(intent: Intent) {
        try {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                Log.d(TAG, "Received shared image: $imageUri")

                // Store the URI in a temporary location for the scanner to access
                val sharedPrefs = getSharedPreferences("coupon_tracker_prefs", MODE_PRIVATE)
                sharedPrefs.edit().putString("shared_image_uri", imageUri.toString()).apply()

                // Navigate to the scanner screen
                navControllerRef?.navigate(Screen.Scanner.route)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling shared image", e)
        }
    }

    private fun handleMultipleSharedImages(intent: Intent) {
        try {
            val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (!imageUris.isNullOrEmpty()) {
                Log.d(TAG, "Received multiple shared images: ${imageUris.size}")

                // Store the URIs as a JSON string for the batch scanner to access
                val gson = com.google.gson.Gson()
                val uriStrings = imageUris.map { it.toString() }
                val uriJson = gson.toJson(uriStrings)

                val sharedPrefs = getSharedPreferences("coupon_tracker_prefs", MODE_PRIVATE)
                sharedPrefs.edit().putString("shared_image_uris", uriJson).apply()

                // Navigate to the batch scanner screen
                navControllerRef?.navigate(Screen.BatchScanner.route)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling multiple shared images", e)
        }
    }

    private fun handleSharedText(intent: Intent) {
        try {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                Log.d(TAG, "Received shared text: $sharedText")

                // Check if it's a URL
                if (sharedText.startsWith("http://") || sharedText.startsWith("https://")) {
                    // Store the URL for the scanner to access
                    val sharedPrefs = getSharedPreferences("coupon_tracker_prefs", MODE_PRIVATE)
                    sharedPrefs.edit().putString("shared_url", sharedText).apply()

                    // Navigate to the manual entry screen
                    navControllerRef?.navigate(Screen.ManualEntry.route)
                } else {
                    // It's probably a coupon code, navigate to manual entry with the code
                    navControllerRef?.navigate(Screen.ManualEntryWithCode.createRoute(sharedText))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling shared text", e)
        }
    }
}