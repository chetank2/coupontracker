package com.example.coupontracker.ui.fragment

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.coupontracker.R
import com.example.coupontracker.databinding.FragmentScannerBinding
import com.example.coupontracker.ui.activity.MultiCouponSelectionActivity
import com.example.coupontracker.ui.viewmodel.ScannerViewModel
import com.example.coupontracker.ui.viewmodel.ScannerUiState
import com.example.coupontracker.util.CouponInfo
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScannerFragment : Fragment() {
    
    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ScannerViewModel by viewModels()
    
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private val TAG = "ScannerFragment"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Snackbar.make(
                binding.root,
                "Camera permission is required to scan coupons",
                Snackbar.LENGTH_LONG
            ).show()
            findNavController().navigateUp()
        }
    }

    private val multiCouponSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val currentBinding = _binding

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val processedCount = data?.getIntExtra(
                MultiCouponSelectionActivity.EXTRA_RESULT_PROCESSED_COUNT,
                0
            ) ?: 0
            val resultType = data?.getStringExtra(MultiCouponSelectionActivity.EXTRA_RESULT_TYPE)
            val explicitMessage = data?.getStringExtra(MultiCouponSelectionActivity.EXTRA_RESULT_MESSAGE)

            val fallbackMessage = when (resultType) {
                MultiCouponSelectionActivity.RESULT_TYPE_ALL_PROCESSED ->
                    getString(R.string.multi_coupon_all_saved_snackbar, processedCount)

                MultiCouponSelectionActivity.RESULT_TYPE_SINGLE_PROCESSED ->
                    getString(R.string.multi_coupon_single_saved_snackbar)

                MultiCouponSelectionActivity.RESULT_TYPE_ALREADY_SAVED ->
                    getString(R.string.multi_coupon_already_saved_snackbar)

                else -> if (processedCount > 0) {
                    getString(R.string.multi_coupon_all_saved_snackbar, processedCount)
                } else {
                    null
                }
            }

            val messageToShow = explicitMessage ?: fallbackMessage
            if (!messageToShow.isNullOrBlank() && currentBinding != null) {
                Snackbar.make(currentBinding.root, messageToShow, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.resetState()
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        // Set up the capture button
        binding.captureButton.setOnClickListener {
            takePhoto()
        }
        
        // Set up the close button
        binding.closeButton.setOnClickListener {
            findNavController().navigateUp()
        }
        
        // Observe processing state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ScannerUiState.Initial -> {
                            binding.progressBar.visibility = View.GONE
                            binding.captureButton.isEnabled = true
                        }
                    is ScannerUiState.Scanning -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.captureButton.isEnabled = false
                    }
                    is ScannerUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true

                        // Show success message with coupon details
                        val coupon = state.coupon
                        val progressLabel = state.miniCpmStatus.displayName()
                        Snackbar.make(
                            binding.root,
                            "Successfully scanned coupon: ${coupon.redeemCode ?: ""} ($progressLabel)",
                            Snackbar.LENGTH_LONG
                        ).show()
                        
                        // In a real app, you might want to navigate to a details screen or edit screen
                        // For now, just show the success message
                    }
                    is ScannerUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true
                        Snackbar.make(
                            binding.root,
                            "Error: ${state.message}",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                    is ScannerUiState.Saved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true
                        // Handle saved state
                        Snackbar.make(
                            binding.root,
                            "Coupon saved successfully!",
                            Snackbar.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }
                    is ScannerUiState.AlreadySaved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true
                        // Handle already saved state
                        Snackbar.make(
                            binding.root,
                            "Coupon already saved: ${state.existingCoupon.redeemCode ?: state.existingCoupon.storeName}",
                            Snackbar.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }
                    is ScannerUiState.MultiCouponDetected -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true

                        val selectionIntent = Intent(
                            requireContext(),
                            MultiCouponSelectionActivity::class.java
                        ).apply {
                            putParcelableArrayListExtra(
                                MultiCouponSelectionActivity.EXTRA_COUPON_INSTANCES,
                                ArrayList(state.couponInstances)
                            )
                            putExtra(
                                MultiCouponSelectionActivity.EXTRA_ORIGINAL_BITMAP,
                                state.originalBitmap
                            )
                            state.imageUri?.let {
                                putExtra(MultiCouponSelectionActivity.EXTRA_IMAGE_URI, it)
                            }
                        }

                        multiCouponSelectionLauncher.launch(selectionIntent)
                    }
                    is ScannerUiState.AllCouponsSaved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.captureButton.isEnabled = true

                        Snackbar.make(
                            binding.root,
                            "Successfully saved ${state.processedCoupons.size} coupons!",
                            Snackbar.LENGTH_LONG
                        ).show()
                        
                        findNavController().navigateUp()
                    }
                }
            }
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }
    
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                "yyyy-MM-dd-HH-mm-ss-SSS",
                Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo saved: $savedUri")
                    
                    // Process the image with our updated ViewModel
                    viewModel.scanImage(savedUri)
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(
                        requireContext(),
                        "Failed to capture image: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
    
    private fun getOutputDirectory(): File {
        // Get the app-specific media directory using modern approach
        val mediaDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            File(requireContext().getExternalFilesDir(null), resources.getString(R.string.app_name)).apply { mkdirs() }
        } else {
            @Suppress("DEPRECATION")
            requireContext().externalMediaDirs.firstOrNull()?.let {
                File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
            }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else requireContext().filesDir
    }
    
    private fun navigateToAddFragment(couponInfo: CouponInfo, imageUri: Uri) {
        val action = ScannerFragmentDirections.actionScannerFragmentToAddFragment(
            couponInfo = couponInfo,
            imageUri = imageUri.toString()
        )
        findNavController().navigate(action)
    }
    
    // This method can be uncommented and implemented when navigation to an edit screen is needed
    /*
    private fun navigateToEditFragment(coupon: com.example.coupontracker.data.model.Coupon, imageUri: Uri?) {
        // Example navigation code, commented out as we don't have the action defined yet
        // val action = ScannerFragmentDirections.actionScannerFragmentToEditFragment(
        //     couponId = coupon.id
        // )
        // findNavController().navigate(action)
    }
    */
    
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }
} 