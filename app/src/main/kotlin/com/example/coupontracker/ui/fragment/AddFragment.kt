package com.example.coupontracker.ui.fragment

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.coupontracker.R
import com.example.coupontracker.databinding.FragmentAddBinding
import com.example.coupontracker.ui.viewmodel.AddCouponViewModel
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.ImageProcessor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.datepicker.MaterialDatePicker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AndroidEntryPoint
class AddFragment : Fragment() {

    private var _binding: FragmentAddBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AddCouponViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var imageUri: Uri? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "AddFragment"
        private const val PREFS_NAME = "CouponTrackerPrefs"
        private const val KEY_USE_MISTRAL_API = "use_mistral_api"
        private const val KEY_MISTRAL_API_KEY = "mistral_api_key"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageUri = it
            viewModel.setImageUri(it)
            displaySelectedImage(it)
            processImageForCouponInfo(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initializeImageProcessor()
        setupCamera()
        setupClickListeners()
        setupMistralApiSwitch()
        observeViewModel()

        // Handle arguments from scanner
        arguments?.let { args ->
            val safeArgs = AddFragmentArgs.fromBundle(args)
            safeArgs.couponInfo?.let { couponInfo ->
                populateFormWithCouponInfo(couponInfo)
            }

            safeArgs.imageUri?.let { uriString ->
                if (uriString.isNotEmpty()) {
                    val uri = Uri.parse(uriString)
                    imageUri = uri
                    viewModel.setImageUri(uri)
                    displaySelectedImage(uri)
                }
            }
        }
    }

    private fun initializeImageProcessor() {
        val useMistralApi = sharedPreferences.getBoolean(KEY_USE_MISTRAL_API, false)
        if (useMistralApi) {
            val key = sharedPreferences.getString(KEY_MISTRAL_API_KEY, null)
            Log.d(TAG, "Using Mistral API with key: ${key?.take(5) ?: "null"}...")
        } else {
            Log.d(TAG, "Mistral API disabled")
        }

        imageProcessor = ImageProcessor(requireContext())
    }

    private fun setupMistralApiSwitch() {
        // Set initial state
        val useMistralApi = sharedPreferences.getBoolean(KEY_USE_MISTRAL_API, false)
        binding.mistralApiSwitch.isChecked = useMistralApi

        // Update visibility of API key input
        binding.mistralApiKeyLayout.visibility = if (useMistralApi) View.VISIBLE else View.GONE

        // Set initial API key value
        val apiKey = sharedPreferences.getString(KEY_MISTRAL_API_KEY, "")
        binding.mistralApiKeyInput.setText(apiKey)
        Log.d(TAG, "Initial API key loaded: ${apiKey?.take(5) ?: "null"}...")

        // Handle switch changes
        binding.mistralApiSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.mistralApiKeyLayout.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Save preference
            sharedPreferences.edit().putBoolean(KEY_USE_MISTRAL_API, isChecked).apply()
            Log.d(TAG, "Mistral API switch set to: $isChecked")

            // If turning off, reinitialize without API key
            if (!isChecked) {
                Log.d(TAG, "Reinitializing ImageProcessor without API key")
                imageProcessor = ImageProcessor(requireContext())

                // Show a message to the user
                Snackbar.make(binding.root, "Mistral API disabled. Using default text extraction.", Snackbar.LENGTH_SHORT).show()
            } else {
                // If turning on and we have a saved key, reinitialize with it
                val savedKey = sharedPreferences.getString(KEY_MISTRAL_API_KEY, null)
                if (!savedKey.isNullOrBlank()) {
                    Log.d(TAG, "Reinitializing ImageProcessor with saved API key: ${savedKey.take(5)}...")
                    imageProcessor = ImageProcessor(requireContext())

                    // Show a message to the user
                    Snackbar.make(binding.root, "Mistral API enabled with saved key.", Snackbar.LENGTH_SHORT).show()
                } else {
                    // Prompt user to enter an API key
                    Snackbar.make(binding.root, "Please enter a Mistral API key.", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        // Handle API key changes
        binding.saveApiKeyButton.setOnClickListener {
            val newApiKey = binding.mistralApiKeyInput.text.toString()
            if (newApiKey.isBlank()) {
                Toast.makeText(requireContext(), "API key cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Log.d(TAG, "Saving new API key: ${newApiKey.take(5)}...")

            // Save to SharedPreferences
            sharedPreferences.edit().putString(KEY_MISTRAL_API_KEY, newApiKey).apply()

            // Reinitialize image processor with new key
            imageProcessor = ImageProcessor(requireContext())

            Snackbar.make(binding.root, "API key saved and activated", Snackbar.LENGTH_SHORT).show()

            // If we have an image already, reprocess it with the new API key
            imageUri?.let { uri ->
                Log.d(TAG, "Reprocessing existing image with new API key")
                processImageForCouponInfo(uri)
            }
        }
    }

    private fun setupCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.captureButton.setOnClickListener {
            captureImage()
        }

        binding.galleryButton.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        binding.expiryDateInput.setOnClickListener {
            showDatePicker()
        }

        binding.saveButton.setOnClickListener {
            saveCoupon()
        }

        binding.mistralInfoButton.setOnClickListener {
            showMistralApiInfo()
        }

        binding.setReminderButton.setOnClickListener {
            showReminderDatePicker()
        }
    }

    private fun showReminderDatePicker() {
        val calendar = Calendar.getInstance()

        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Reminder Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            calendar.timeInMillis = selection

            // Show time picker after date is selected
            showReminderTimePicker(calendar)
        }

        datePicker.show(parentFragmentManager, "REMINDER_DATE_PICKER")
    }

    private fun showReminderTimePicker(calendar: Calendar) {
        val timePickerDialog = android.app.TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)

                // Set the reminder date in the view model
                viewModel.setReminderDate(calendar.time)

                // Update UI to show selected reminder date/time
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                binding.reminderDateText.text = "Reminder set for: ${dateFormat.format(calendar.time)}"
                binding.reminderDateText.visibility = View.VISIBLE
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        )

        timePickerDialog.show()
    }

    private fun showMistralApiInfo() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("About Mistral OCR AI")
            .setMessage(
                "Mistral AI provides advanced OCR capabilities that can improve coupon text extraction. " +
                "To use this feature, you need to:\n\n" +
                "1. Create an account at mistral.ai\n" +
                "2. Generate an API key\n" +
                "3. Enter the API key here\n\n" +
                "Note: Using the Mistral API may incur charges based on your usage."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.couponSaved.collect { saved ->
                    if (saved) {
                        findNavController().navigate(
                            AddFragmentDirections.actionAddToDetail(viewModel.couponId)
                        )
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collect { error ->
                    error?.let {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(requireContext(), "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(requireContext().contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uri ->
                        imageUri = uri
                        viewModel.setImageUri(uri)
                        displaySelectedImage(uri)
                        processImageForCouponInfo(uri)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Failed to capture image", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun displaySelectedImage(uri: Uri) {
        // Hide the camera preview and show the selected image
        binding.viewFinder.visibility = View.GONE
        binding.selectedImageView.visibility = View.VISIBLE

        // Load the image using Glide
        Glide.with(requireContext())
            .load(uri)
            .placeholder(R.drawable.ic_image_placeholder)
            .error(R.drawable.ic_image_error)
            .centerCrop()
            .into(binding.selectedImageView)

        // Show a message to confirm image selection
        Toast.makeText(requireContext(), "Image selected successfully", Toast.LENGTH_SHORT).show()
    }

    private fun processImageForCouponInfo(uri: Uri) {
        binding.processingIndicator.visibility = View.VISIBLE

        // Clear any previous error messages
        binding.errorText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d(TAG, "Processing image for coupon info: $uri")
                // Check if Mistral API is enabled and we have a key
                val useMistralApi = sharedPreferences.getBoolean(KEY_USE_MISTRAL_API, false)
                val apiKey = sharedPreferences.getString(KEY_MISTRAL_API_KEY, null)

                if (useMistralApi && apiKey.isNullOrBlank()) {
                    Log.w(TAG, "Mistral API is enabled but no API key is set")
                    binding.errorText.text = "Please set a Mistral API key"
                    binding.errorText.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "Please set a Mistral API key", Toast.LENGTH_SHORT).show()
                    binding.processingIndicator.visibility = View.GONE
                    return@launch
                }

                // Ensure ImageProcessor has the latest API key
                if (useMistralApi && !apiKey.isNullOrBlank()) {
                    Log.d(TAG, "Using Mistral API with key: ${apiKey.take(5)}...")
                    imageProcessor = ImageProcessor(requireContext())
                } else {
                    Log.d(TAG, "Using default text extraction (no Mistral API)")
                    imageProcessor = ImageProcessor(requireContext())
                }

                val couponInfo = imageProcessor.processImage(uri)
                Log.d(TAG, "Extracted coupon info: $couponInfo")

                if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                    couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                    Log.w(TAG, "No coupon information was extracted")
                    binding.errorText.text = "Could not extract coupon information from image. Try adjusting the image or entering details manually."
                    binding.errorText.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "Could not extract coupon information from image", Toast.LENGTH_SHORT).show()
                } else {
                    populateFormWithCouponInfo(couponInfo)
                    Toast.makeText(requireContext(), "Coupon information extracted successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                binding.errorText.text = "Error: ${e.message}"
                binding.errorText.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Failed to extract coupon information: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingIndicator.visibility = View.GONE
            }
        }
    }

    private fun populateFormWithCouponInfo(couponInfo: CouponInfo) {
        with(binding) {
            storeNameInput.setText(couponInfo.storeName)
            descriptionInput.setText(couponInfo.description)

            couponInfo.expiryDate?.let { date ->
                expiryDateInput.setText(SimpleDateFormat("MM/dd/yyyy", Locale.US).format(date))
                viewModel.setExpiryDate(date)
            }

            couponInfo.cashbackAmount?.let { amount ->
                cashbackAmountInput.setText(amount.toString())
            }

            couponInfo.redeemCode?.let { code ->
                redeemCodeInput.setText(code)
            }

            couponInfo.category?.let { category ->
                categoryInput.setText(category)
            }

            couponInfo.rating?.let { rating ->
                ratingInput.setText(rating)
            }

            couponInfo.status?.let { status ->
                statusInput.setText(status)
            }

            // Populate new fields
            couponInfo.minimumPurchase?.let { minPurchase ->
                minimumPurchaseInput.setText(minPurchase.toString())
            }

            couponInfo.maximumDiscount?.let { maxDiscount ->
                maximumDiscountInput.setText(maxDiscount.toString())
            }

            couponInfo.paymentMethod?.let { method ->
                paymentMethodInput.setText(method)
            }

            couponInfo.platformType?.let { platform ->
                platformTypeInput.setText(platform)
            }

            couponInfo.usageLimit?.let { limit ->
                usageLimitInput.setText(limit.toString())
            }
        }
    }

    private fun showDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Expiry Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            val date = Date(selection)
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            binding.expiryDateInput.setText(dateFormat.format(date))
            viewModel.setExpiryDate(date)
        }

        datePicker.show(parentFragmentManager, "DATE_PICKER")
    }

    private fun saveCoupon() {
        val storeName = binding.storeNameInput.text.toString()
        val description = binding.descriptionInput.text.toString()
        val cashbackAmount = binding.cashbackAmountInput.text.toString().toDoubleOrNull()
        val redeemCode = binding.redeemCodeInput.text.toString()
        val category = binding.categoryInput.text.toString()
        val rating = binding.ratingInput.text.toString()
        val status = binding.statusInput.text.toString()

        // Get new fields
        val minimumPurchase = binding.minimumPurchaseInput.text.toString().toDoubleOrNull()
        val maximumDiscount = binding.maximumDiscountInput.text.toString().toDoubleOrNull()
        val paymentMethod = binding.paymentMethodInput.text.toString()
        val platformType = binding.platformTypeInput.text.toString()
        val usageLimit = binding.usageLimitInput.text.toString().toIntOrNull()
        val isPriority = binding.priorityCheckbox.isChecked

        if (storeName.isBlank() || description.isBlank() || cashbackAmount == null) {
            Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
            return
        }

        // Set priority in view model
        viewModel.setPriority(isPriority)

        viewModel.saveCoupon(
            storeName = storeName,
            description = description,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode.takeIf { it.isNotBlank() },
            category = category.takeIf { it.isNotBlank() },
            rating = rating.takeIf { it.isNotBlank() },
            status = status.takeIf { it.isNotBlank() },
            minimumPurchase = minimumPurchase,
            maximumDiscount = maximumDiscount,
            paymentMethod = paymentMethod.takeIf { it.isNotBlank() },
            platformType = platformType.takeIf { it.isNotBlank() },
            usageLimit = usageLimit
        )
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }
}