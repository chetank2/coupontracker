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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.coupontracker.ui.navigation.AppNavigation
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.CouponTrackerTheme
import com.example.coupontracker.util.ThemeManager
import com.example.coupontracker.util.ThemeMode
import com.example.coupontracker.util.CouponUriMigrationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // Reference to the NavController for navigation from non-composable functions
    private var navControllerRef: NavController? = null

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var uriMigrationManager: CouponUriMigrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate started")

        setContent {
            // Collect the current theme mode
            val themeMode by themeManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            CouponTrackerTheme(themeMode = themeMode) {
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

        // Defer URI migration until after first frame is rendered (performance optimization)
        window.decorView.post {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    uriMigrationManager.performMigrationIfNeeded()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during URI migration", e)
                }
            }
        }

        Log.d(TAG, "MainActivity onCreate completed successfully")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedContent(intent)
    }

    private fun handleSharedContent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.isImageLikeShare()) {
                    handleSharedImage(intent)
                } else if (intent.type == "text/plain") {
                    handleSharedText(intent)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.isImageLikeShare()) {
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
                persistReadPermissionIfAvailable(intent, imageUri)
                val scannerUri = copySharedImageToCache(imageUri) ?: imageUri

                // Store the URI in a temporary location for the scanner to access
                val sharedPrefs = getSharedPreferences("coupon_tracker_prefs", MODE_PRIVATE)
                sharedPrefs.edit().putString("shared_image_uri", scannerUri.toString()).apply()

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
                val uriStrings = imageUris.map { uri ->
                    persistReadPermissionIfAvailable(intent, uri)
                    (copySharedImageToCache(uri) ?: uri).toString()
                }
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

    private fun copySharedImageToCache(uri: Uri): Uri? {
        return try {
            val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }
            val extension = when {
                contentResolver.getType(uri)?.contains("png", ignoreCase = true) == true -> "png"
                contentResolver.getType(uri)?.contains("webp", ignoreCase = true) == true -> "webp"
                else -> "jpg"
            }
            val outputFile = File(sharedDir, "shared_${System.currentTimeMillis()}.$extension")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val cachedUri = Uri.fromFile(outputFile)
            Log.d(TAG, "Copied shared image to cache: $cachedUri")
            cachedUri
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy shared image into app cache: $uri", e)
            null
        }
    }

    private fun Intent.isImageLikeShare(): Boolean {
        return type?.startsWith("image/") == true || type == "application/octet-stream"
    }

    private fun persistReadPermissionIfAvailable(intent: Intent, uri: Uri) {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (intent.flags and readFlag == 0) {
            return
        }

        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            Log.d(TAG, "Shared URI has transient read permission only: $uri")
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, readFlag)
            Log.d(TAG, "Persisted read permission for shared URI: $uri")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist read permission for shared URI: $uri", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Shared URI does not support persistable permission: $uri", e)
        }
    }
}
