package com.example.coupontracker.ui.activity

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.coupontracker.R
import com.example.coupontracker.databinding.ActivityMultiCouponSelectionBinding
import com.example.coupontracker.databinding.ItemCouponSelectionBinding
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.CouponStatus
import com.example.coupontracker.ml.FieldType
import com.example.coupontracker.ml.FieldDetection
import com.example.coupontracker.ui.viewmodel.ScannerViewModel
import com.example.coupontracker.ui.viewmodel.ScannerUiState
import com.example.coupontracker.ui.view.MultiCouponOverlayView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class MultiCouponSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiCouponSelectionBinding
    private val viewModel: ScannerViewModel by viewModels()
    private lateinit var adapter: CouponSelectionAdapter

    private var couponInstances: MutableList<CouponInstance> = mutableListOf()
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

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> {
                    binding.multiCouponOverlay.selectAll()
                    true
                }
                R.id.action_add_tile -> {
                    val addMode = binding.multiCouponOverlay.toggleAddTileMode()
                    item.title = if (addMode) {
                        getString(R.string.action_cancel_add_tile)
                    } else {
                        getString(R.string.action_add_tile)
                    }
                    if (addMode) {
                        Toast.makeText(this, R.string.overlay_draw_instruction, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_delete -> {
                    binding.multiCouponOverlay.deleteSelected()
                    true
                }
                else -> false
            }
        }

        binding.multiCouponOverlay.setOverlayListener(object : MultiCouponOverlayView.OverlayListener {
            override fun onSelectionChanged(selectedIds: Set<String>) {
                adapter.setSelectedIds(selectedIds)
            }

            override fun onCouponBoundingBoxChanged(couponId: String, newBoundingBox: RectF) {
                handleCouponBoundingBoxChanged(couponId, newBoundingBox)
            }

            override fun onAddCouponRequested(boundingBox: RectF) {
                handleAddTile(boundingBox)
            }

            override fun onDeleteRequested(selectedIds: Set<String>) {
                handleDeleteCoupons(selectedIds)
            }
        })

        binding.processSelectedButton.setOnClickListener {
            processSelectedCoupons()
        }

        binding.processAllButton.setOnClickListener {
            processAllCoupons()
        }
    }

    private fun extractIntentData() {
        Log.d(TAG, "Extracting intent data for multi-coupon selection")
    }

    private fun setupRecyclerView() {
        adapter = CouponSelectionAdapter(
            onCouponClick = { instance, _ ->
                viewModel.selectCoupon(instance, imageUri)
            },
            onSelectionChanged = { selectedIds ->
                binding.multiCouponOverlay.setSelectedCouponIds(selectedIds)
            }
        )

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

                        viewModel.saveCoupon(state.coupon)
                    }
                    is ScannerUiState.AllCouponsSaved -> {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonsContainer.visibility = View.VISIBLE

                        Toast.makeText(
                            this@MultiCouponSelectionActivity,
                            "Successfully saved ${state.coupons.size} coupons",
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

        viewModel.resetManualOverrides()
        binding.multiCouponOverlay.setImageBitmap(bitmap)
        couponInstances = instances.map { cloneInstance(it) }.toMutableList()
        binding.multiCouponOverlay.setCouponInstances(couponInstances)
        binding.multiCouponOverlay.clearSelection()

        adapter.updateCoupons(couponInstances)

        binding.processSelectedButton.visibility = View.VISIBLE
        binding.processAllButton.visibility = View.VISIBLE
        updateDetectedCount()
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

    fun setCouponInstances(instances: List<CouponInstance>, bitmap: Bitmap, uri: String?) {
        originalBitmap = bitmap
        imageUri = uri
        displayCoupons(instances, bitmap)
    }

    private fun handleCouponBoundingBoxChanged(couponId: String, newBoundingBox: RectF) {
        val bitmap = originalBitmap ?: return
        val index = couponInstances.indexOfFirst { it.id == couponId }
        if (index == -1) return

        val updatedInstance = cloneInstance(couponInstances[index]).copy(
            boundingBox = RectF(newBoundingBox),
            cropBitmap = createCropBitmap(bitmap, newBoundingBox)
        )
        couponInstances[index] = updatedInstance
        adapter.updateCoupon(updatedInstance)
        binding.multiCouponOverlay.updateCouponInstance(updatedInstance)
        viewModel.updateManualCouponInstance(updatedInstance)
    }

    private fun handleAddTile(boundingBox: RectF) {
        val bitmap = originalBitmap ?: run {
            Toast.makeText(this, R.string.overlay_missing_bitmap, Toast.LENGTH_SHORT).show()
            return
        }

        val sanitizedRect = RectF(
            boundingBox.left.coerceAtLeast(0f),
            boundingBox.top.coerceAtLeast(0f),
            boundingBox.right.coerceAtMost(bitmap.width.toFloat()),
            boundingBox.bottom.coerceAtMost(bitmap.height.toFloat())
        )

        val cropBitmap = createCropBitmap(bitmap, sanitizedRect)
        val newInstance = CouponInstance(
            id = UUID.randomUUID().toString(),
            boundingBox = sanitizedRect,
            status = CouponStatus.COMPLETE,
            confidence = 1f,
            fields = emptyList(),
            cropBitmap = cropBitmap
        )

        couponInstances.add(newInstance)
        adapter.updateCoupons(couponInstances)
        binding.multiCouponOverlay.addCouponInstance(newInstance)
        updateDetectedCount()
        viewModel.updateManualCouponInstance(newInstance)

        binding.multiCouponOverlay.exitAddTileMode()
        binding.toolbar.menu.findItem(R.id.action_add_tile)?.title = getString(R.string.action_add_tile)
    }

    private fun handleDeleteCoupons(selectedIds: Set<String>) {
        if (selectedIds.isEmpty()) return
        couponInstances = couponInstances.filter { it.id !in selectedIds }.map { cloneInstance(it) }.toMutableList()
        adapter.updateCoupons(couponInstances)
        binding.multiCouponOverlay.setCouponInstances(couponInstances)
        binding.multiCouponOverlay.clearSelection()
        updateDetectedCount()
        viewModel.removeManualCoupons(selectedIds)
    }

    private fun cloneInstance(instance: CouponInstance): CouponInstance {
        val clonedFields = instance.fields.map { field -> cloneField(field) }
        return instance.copy(
            boundingBox = RectF(instance.boundingBox),
            fields = clonedFields
        )
    }

    private fun cloneField(field: FieldDetection): FieldDetection {
        return field.copy(boundingBox = RectF(field.boundingBox))
    }

    private fun createCropBitmap(source: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.coerceIn(0f, (source.width - 1).toFloat()).toInt()
        val top = rect.top.coerceIn(0f, (source.height - 1).toFloat()).toInt()
        val right = rect.right.coerceIn((left + 1).toFloat(), source.width.toFloat()).toInt()
        val bottom = rect.bottom.coerceIn((top + 1).toFloat(), source.height.toFloat()).toInt()
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun updateDetectedCount() {
        binding.detectedCountText.text = getString(R.string.detected_coupon_count, couponInstances.size)
    }
}

class CouponSelectionAdapter(
    private val onCouponClick: (CouponInstance, Int) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<CouponSelectionAdapter.CouponViewHolder>() {

    private var coupons: MutableList<CouponInstance> = mutableListOf()
    private val selectedIds = linkedSetOf<String>()

    fun updateCoupons(newCoupons: List<CouponInstance>) {
        coupons = newCoupons.toMutableList()
        val validIds = coupons.map { it.id }.toSet()
        selectedIds.retainAll(validIds)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(coupons.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds)
    }

    fun getSelectedCoupons(): List<CouponInstance> {
        return coupons.filter { selectedIds.contains(it.id) }
    }

    fun setSelectedIds(ids: Set<String>) {
        if (selectedIds == ids) return
        selectedIds.clear()
        selectedIds.addAll(ids.intersect(coupons.map { it.id }.toSet()))
        notifyDataSetChanged()
    }

    fun updateCoupon(updated: CouponInstance) {
        val index = coupons.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            coupons[index] = updated
            notifyItemChanged(index)
        }
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
        holder.bind(coupons[position], position, selectedIds.contains(coupons[position].id))
    }

    override fun getItemCount(): Int = coupons.size

    inner class CouponViewHolder(
        private val binding: ItemCouponSelectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(coupon: CouponInstance, position: Int, isSelected: Boolean) {
            binding.couponImageView.setImageBitmap(coupon.cropBitmap)

            binding.couponNumberText.text = "Coupon ${position + 1}"

            binding.statusText.text = when (coupon.status) {
                CouponStatus.COMPLETE -> "Complete"
                CouponStatus.PARTIAL_TOP -> "Partial (Top)"
                CouponStatus.PARTIAL_BOTTOM -> "Partial (Bottom)"
            }

            binding.fieldsCountText.text = "${coupon.fields.size} fields detected"

            binding.confidenceText.text = "Confidence: ${String.format("%.1f%%", coupon.confidence * 100)}"

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

            binding.selectionCheckbox.setOnCheckedChangeListener(null)
            binding.selectionCheckbox.isChecked = isSelected
            binding.root.isSelected = isSelected

            binding.root.setOnClickListener {
                toggleSelection(coupon)
                notifyItemChanged(position)
            }

            binding.processButton.setOnClickListener {
                onCouponClick(coupon, position)
            }

            binding.selectionCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedIds.add(coupon.id)
                } else {
                    selectedIds.remove(coupon.id)
                }
                binding.root.isSelected = isChecked
                onSelectionChanged(selectedIds)
            }
        }

        private fun toggleSelection(coupon: CouponInstance) {
            if (!selectedIds.add(coupon.id)) {
                selectedIds.remove(coupon.id)
            }
            onSelectionChanged(selectedIds)
        }
    }
}
