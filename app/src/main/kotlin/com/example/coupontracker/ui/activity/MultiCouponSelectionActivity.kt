package com.example.coupontracker.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.coupontracker.R
import com.example.coupontracker.databinding.ActivityMultiCouponSelectionBinding
import com.example.coupontracker.databinding.ItemCouponSelectionBinding
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.CouponStatus
import com.example.coupontracker.ml.FieldType
import com.example.coupontracker.ui.viewmodel.CouponExtractionStatus
import com.example.coupontracker.ui.viewmodel.CouponProcessingStage
import com.example.coupontracker.ui.viewmodel.ScannerViewModel
import com.example.coupontracker.ui.viewmodel.ScannerUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MultiCouponSelectionActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMultiCouponSelectionBinding
    private val viewModel: ScannerViewModel by viewModels()
    private lateinit var adapter: CouponSelectionAdapter
    
    private var couponInstances: List<CouponInstance> = emptyList()
    private var originalBitmap: Bitmap? = null
    private var imageUri: String? = null
    
    companion object {
        private const val TAG = "MultiCouponSelection"
        const val EXTRA_COUPON_INSTANCES = "coupon_instances"
        const val EXTRA_ORIGINAL_BITMAP = "original_bitmap"
        const val EXTRA_IMAGE_URI = "image_uri"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiCouponSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        extractIntentData()
        setupRecyclerView()
        observeViewModel()
    }
    
    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        binding.selectAllButton.setOnClickListener {
            adapter.selectAll()
        }
        
        binding.processSelectedButton.setOnClickListener {
            processSelectedCoupons()
        }
        
        binding.processAllButton.setOnClickListener {
            processAllCoupons()
        }
    }
    
    private fun extractIntentData() {
        // In a real implementation, you would pass data through Intent extras
        // For now, we'll use a simple approach
        
        // This is a simplified approach - in practice, you might use Parcelable or pass data differently
        Log.d(TAG, "Extracting intent data for multi-coupon selection")
    }
    
    private fun setupRecyclerView() {
        adapter = CouponSelectionAdapter { instance, _ ->
            // Handle single coupon selection
            viewModel.selectCoupon(instance, imageUri)
        }
        
        binding.couponsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.couponsRecyclerView.adapter = adapter
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is ScannerUiState.Initial -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE
                    }
                    is ScannerUiState.Scanning -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.buttonsContainer.visibility = View.GONE
                    }
                    is ScannerUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE
                        
                        Toast.makeText(
                            this@MultiCouponSelectionActivity,
                            "Coupon processed successfully: ${state.coupon.redeemCode}",
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Save the coupon
                        viewModel.saveCoupon(state.coupon)
                    }
                    is ScannerUiState.ProcessingCoupons -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.buttonsContainer.visibility = View.GONE
                        adapter.updateProcessingStatuses(state.statuses)
                    }
                    is ScannerUiState.AllCouponsSaved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE

                        adapter.updateProcessingStatuses(state.statuses)

                        val failedCount = state.statuses.count { it.stage == CouponProcessingStage.FALLBACK }
                        val savedCount = state.coupons.size
                        val message = if (failedCount > 0) {
                            "Saved $savedCount coupons, $failedCount failed"
                        } else {
                            "Successfully saved $savedCount coupons"
                        }

                        Toast.makeText(
                            this@MultiCouponSelectionActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                        
                        finish()
                    }
                    is ScannerUiState.Saved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE
                        
                        Toast.makeText(
                            this@MultiCouponSelectionActivity,
                            "Coupon saved successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        finish()
                    }
                    is ScannerUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE
                        
                        Toast.makeText(
                            this@MultiCouponSelectionActivity,
                            "Error: ${state.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ScannerUiState.MultiCouponDetected -> {
                        // This state should be handled in the calling activity
                        couponInstances = state.couponInstances
                        originalBitmap = state.originalBitmap
                        imageUri = state.imageUri
                        
                        displayCoupons(state.couponInstances, state.originalBitmap)
                    }
                }
            }
        }
    }
    
    private fun displayCoupons(instances: List<CouponInstance>, bitmap: Bitmap) {
        Log.d(TAG, "Displaying ${instances.size} detected coupons")
        
        // Update header info
        binding.detectedCountText.text = "Detected ${instances.size} coupons"
        
        // Show original image with overlays
        val overlayBitmap = createOverlayBitmap(bitmap, instances)
        binding.originalImageView.setImageBitmap(overlayBitmap)
        
        // Update adapter
        adapter.updateCoupons(instances)
        
        // Update button states
        binding.selectAllButton.visibility = View.VISIBLE
        binding.processSelectedButton.visibility = View.VISIBLE
        binding.processAllButton.visibility = View.VISIBLE
    }
    
    private fun createOverlayBitmap(originalBitmap: Bitmap, instances: List<CouponInstance>): Bitmap {
        val overlayBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)
        
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
        
        val textPaint = Paint().apply {
            textSize = 48f
            isAntiAlias = true
            color = ContextCompat.getColor(this@MultiCouponSelectionActivity, android.R.color.white)
        }
        
        instances.forEachIndexed { index, instance ->
            // Set color based on coupon status
            paint.color = when (instance.status) {
                CouponStatus.COMPLETE -> ContextCompat.getColor(this, android.R.color.holo_green_light)
                CouponStatus.PARTIAL_TOP -> ContextCompat.getColor(this, android.R.color.holo_orange_light)
                CouponStatus.PARTIAL_BOTTOM -> ContextCompat.getColor(this, android.R.color.holo_red_light)
            }
            
            // Draw bounding box
            canvas.drawRect(instance.boundingBox, paint)
            
            // Draw coupon number
            val label = "${index + 1}"
            canvas.drawText(
                label,
                instance.boundingBox.left + 10f,
                instance.boundingBox.top + 60f,
                textPaint
            )
            
            // Draw field overlays
            drawFieldOverlays(canvas, instance)
        }
        
        return overlayBitmap
    }
    
    private fun drawFieldOverlays(canvas: Canvas, instance: CouponInstance) {
        val fieldPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        
        instance.fields.forEach { field ->
            fieldPaint.color = when (field.fieldType) {
                FieldType.CODE_REGION -> ContextCompat.getColor(this, R.color.field_code)
                FieldType.BENEFIT_REGION -> ContextCompat.getColor(this, R.color.field_benefit)
                FieldType.EXPIRY_REGION -> ContextCompat.getColor(this, R.color.field_expiry)
                FieldType.APP_REGION -> ContextCompat.getColor(this, R.color.field_app)
                FieldType.TERMS_REGION -> ContextCompat.getColor(this, R.color.field_terms)
            }
            
            canvas.drawRect(field.boundingBox, fieldPaint)
        }
    }
    
    private fun processSelectedCoupons() {
        val selectedCoupons = adapter.getSelectedCoupons()
        
        if (selectedCoupons.isEmpty()) {
            Toast.makeText(this, "Please select at least one coupon", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "Processing ${selectedCoupons.size} selected coupons")
        viewModel.processAllCoupons(selectedCoupons, imageUri)
    }
    
    private fun processAllCoupons() {
        Log.d(TAG, "Processing all ${couponInstances.size} detected coupons")
        viewModel.processAllCoupons(couponInstances, imageUri)
    }
    
    /**
     * Set the coupon instances to display (called from parent activity)
     */
    fun setCouponInstances(instances: List<CouponInstance>, bitmap: Bitmap, uri: String?) {
        couponInstances = instances
        originalBitmap = bitmap
        imageUri = uri
        displayCoupons(instances, bitmap)
    }
}

/**
 * Adapter for coupon selection RecyclerView
 */
class CouponSelectionAdapter(
    private val onCouponClick: (CouponInstance, Int) -> Unit
) : RecyclerView.Adapter<CouponSelectionAdapter.CouponViewHolder>() {

    private var coupons: List<CouponInstance> = emptyList()
    private val selectedPositions = mutableSetOf<Int>()
    private var processingStatuses: Map<Int, CouponExtractionStatus> = emptyMap()

    fun updateCoupons(newCoupons: List<CouponInstance>) {
        coupons = newCoupons
        processingStatuses = emptyMap()
        notifyDataSetChanged()
    }

    fun updateProcessingStatuses(statuses: List<CouponExtractionStatus>) {
        processingStatuses = statuses.associateBy { it.index }
        notifyDataSetChanged()
    }
    
    fun selectAll() {
        selectedPositions.clear()
        selectedPositions.addAll(0 until coupons.size)
        notifyDataSetChanged()
    }
    
    fun getSelectedCoupons(): List<CouponInstance> {
        return selectedPositions.map { coupons[it] }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CouponViewHolder {
        val binding = ItemCouponSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CouponViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: CouponViewHolder, position: Int) {
        holder.bind(coupons[position], position, selectedPositions.contains(position))
    }
    
    override fun getItemCount(): Int = coupons.size
    
    inner class CouponViewHolder(
        private val binding: ItemCouponSelectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(coupon: CouponInstance, position: Int, isSelected: Boolean) {
            binding.couponImageView.setImageBitmap(coupon.cropBitmap)

            binding.couponNumberText.text = "Coupon ${position + 1}"

            val extractionStatus = processingStatuses[position]
            if (extractionStatus != null) {
                val message = extractionStatus.message?.takeIf { it.isNotBlank() }
                binding.statusText.text = buildString {
                    append(extractionStatus.stage.label)
                    if (message != null) {
                        append(": ")
                        append(message)
                    }
                }
                val enabled = extractionStatus.stage == CouponProcessingStage.QUEUED
                binding.processButton.isEnabled = enabled
                binding.selectionCheckbox.isEnabled = enabled
                binding.root.isEnabled = enabled
            } else {
                binding.statusText.text = when (coupon.status) {
                    CouponStatus.COMPLETE -> "Complete"
                    CouponStatus.PARTIAL_TOP -> "Partial (Top)"
                    CouponStatus.PARTIAL_BOTTOM -> "Partial (Bottom)"
                }
                binding.processButton.isEnabled = true
                binding.selectionCheckbox.isEnabled = true
                binding.root.isEnabled = true
            }

            binding.fieldsCountText.text = "${coupon.fields.size} fields detected"

            binding.confidenceText.text = "Confidence: ${String.format("%.1f%%", coupon.confidence * 100)}"
            
            // Show detected fields
            val fieldsList = coupon.fields.joinToString(", ") { field ->
                when (field.fieldType) {
                    FieldType.CODE_REGION -> "Code"
                    FieldType.BENEFIT_REGION -> "Benefit"
                    FieldType.EXPIRY_REGION -> "Expiry"
                    FieldType.APP_REGION -> "App"
                    FieldType.TERMS_REGION -> "Terms"
                }
            }
            binding.detectedFieldsText.text = if (fieldsList.isNotEmpty()) fieldsList else "No fields detected"
            
            // Set selection state
            binding.selectionCheckbox.isChecked = isSelected
            binding.root.isSelected = isSelected
            
            // Set click listeners
            binding.root.setOnClickListener {
                if (selectedPositions.contains(position)) {
                    selectedPositions.remove(position)
                } else {
                    selectedPositions.add(position)
                }
                notifyItemChanged(position)
            }
            
            binding.processButton.setOnClickListener {
                onCouponClick(coupon, position)
            }
            
            binding.selectionCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPositions.add(position)
                } else {
                    selectedPositions.remove(position)
                }
                binding.root.isSelected = isChecked
            }
        }
    }
}
