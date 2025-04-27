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
import com.example.coupontracker.ui.navigation.AppNavigation
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.CouponTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate started")
        
        setContent {
            CouponTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
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
                // In a real app, you would navigate to the scanner screen with the image URI
                // For now, we'll just log it
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
                // Process the first image for now
                // In a real app, you would navigate to the scanner screen with the image URI
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
                // In a real app, you would navigate to the add coupon screen with the text
                // For now, we'll just log it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling shared text", e)
        }
    }
} 