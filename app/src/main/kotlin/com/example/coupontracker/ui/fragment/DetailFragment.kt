package com.example.coupontracker.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.coupontracker.R
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.databinding.FragmentDetailBinding
import com.example.coupontracker.ui.viewmodel.DetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@AndroidEntryPoint
class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DetailViewModel by viewModels()
    private val args: DetailFragmentArgs by navArgs()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMenu()
        setupObservers()
        setupClickListeners()
        viewModel.loadCoupon(args.couponId)
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_detail, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_edit -> {
                        findNavController().navigate(
                            DetailFragmentDirections.actionDetailFragmentToEditFragment(args.couponId)
                        )
                        true
                    }
                    R.id.action_delete -> {
                        viewModel.deleteCoupon()
                        findNavController().navigateUp()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }
    
    private fun setupClickListeners() {
        binding.copyButton.setOnClickListener {
            val code = binding.couponCode.text.toString()
            if (code.isNotEmpty() && code != "No code available") {
                copyToClipboard(code)
            }
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.coupon.collect { coupon ->
                    coupon?.let {
                        binding.apply {
                            storeName.text = coupon.storeName
                            
                            // Show full description
                            description.text = coupon.description
                            
                            // Always format cashback amount with rupee symbol
                            if (coupon.cashbackAmount > 0) {
                                cashbackAmount.text = "₹${coupon.cashbackAmount.toInt()}"
                                cashbackAmount.visibility = View.VISIBLE
                            } else {
                                cashbackAmount.visibility = View.GONE
                            }
                            
                            expiryDate.text = "Expires: ${dateFormat.format(coupon.expiryDate)}"
                            
                            // Show category if available
                            coupon.category?.let { category ->
                                categoryChip.text = category
                                categoryChip.visibility = View.VISIBLE
                            } ?: run {
                                categoryChip.visibility = View.GONE
                            }
                            
                            // Show rating if available
                            coupon.rating?.let { rating ->
                                ratingChip.text = rating
                                ratingChip.visibility = View.VISIBLE
                            } ?: run {
                                ratingChip.visibility = View.GONE
                            }
                            
                            // Show status if available
                            coupon.status?.let { status ->
                                statusChip.text = status
                                statusChip.visibility = View.VISIBLE
                            } ?: run {
                                statusChip.visibility = View.GONE
                            }
                            
                            // Show redeem code if available
                            coupon.redeemCode?.let { code ->
                                couponCode.text = code
                                couponCode.setTextIsSelectable(true)
                            } ?: run {
                                couponCode.text = "No code available"
                            }
                            
                            // Load store image if available
                            coupon.imageUri?.let { uri ->
                                val imageUri = Uri.parse(uri)
                                val requestOptions = RequestOptions()
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .placeholder(R.drawable.ic_image_placeholder)
                                    .error(R.drawable.ic_image_placeholder)

                                Glide.with(requireContext())
                                    .load(imageUri)
                                    .apply(requestOptions)
                                    .into(storeImage)
                            } ?: run {
                                // For ABHIBUS, load a default image or logo
                                if (coupon.storeName.equals("ABHIBUS", ignoreCase = true)) {
                                    storeImage.setImageResource(R.drawable.ic_image_placeholder)
                                } else {
                                    storeImage.setImageResource(R.drawable.ic_image_placeholder)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Coupon Code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Code copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 