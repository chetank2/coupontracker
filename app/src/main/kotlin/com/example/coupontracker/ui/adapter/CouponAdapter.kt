package com.example.coupontracker.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.coupontracker.R
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.databinding.ItemCouponBinding
import com.example.coupontracker.ui.components.DateFormatter
import com.example.coupontracker.ui.components.StatusType

class CouponAdapter(
    private val onItemClick: (Long) -> Unit,
    private val onCopyCodeClick: (String) -> Unit
) : ListAdapter<Coupon, CouponAdapter.CouponViewHolder>(CouponDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CouponViewHolder {
        val binding = ItemCouponBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CouponViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CouponViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CouponViewHolder(
        private val binding: ItemCouponBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position).id)
                }
            }

            binding.copyCodeButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    getItem(position).redeemCode?.let { code ->
                        onCopyCodeClick(code)
                    }
                }
            }
        }

        fun bind(coupon: Coupon) {
            binding.apply {
                couponCode.text = coupon.redeemCode ?: "NO CODE"
                
                // Limit description length if needed
                val maxDescLength = 100
                val desc = if (coupon.description.length > maxDescLength) {
                    coupon.description.substring(0, maxDescLength) + "..."
                } else {
                    coupon.description
                }
                couponDescription.text = desc
                
                // Always format cashback amount with rupee symbol
                couponValue.text = String.format("₹%.0f", coupon.cashbackAmount)
                
                // Set expiry date text with color based on days remaining
                setExpiryDateText(coupon.expiryDate)
                
                // Load store icon using Glide
                coupon.imageUri?.let { uri ->
                    val imageUri = Uri.parse(uri)
                    val requestOptions = RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_placeholder)

                    Glide.with(itemView.context)
                        .load(imageUri)
                        .apply(requestOptions)
                        .into(storeIcon)
                } ?: run {
                    storeIcon.setImageResource(R.drawable.ic_image_placeholder)
                }

                // Show/hide copy code button
                copyCodeButton.visibility = if (coupon.redeemCode != null) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }
        
        private fun setExpiryDateText(expiryDate: java.util.Date?) {
            val context = binding.root.context
            val status = DateFormatter.getExpiryStatus(expiryDate)
            val badgeColor = when (status) {
                StatusType.ERROR -> ContextCompat.getColor(context, R.color.expired)
                StatusType.WARNING -> ContextCompat.getColor(context, R.color.expiring_soon)
                StatusType.SUCCESS -> ContextCompat.getColor(context, R.color.valid)
                StatusType.INFO, StatusType.NEUTRAL -> ContextCompat.getColor(context, R.color.info)
            }

            binding.expiryText.apply {
                text = DateFormatter.getExpiryText(expiryDate)
                background.setTint(badgeColor)
            }
        }
    }

    class CouponDiffCallback : DiffUtil.ItemCallback<Coupon>() {
        override fun areItemsTheSame(oldItem: Coupon, newItem: Coupon): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Coupon, newItem: Coupon): Boolean {
            return oldItem == newItem
        }
    }
} 