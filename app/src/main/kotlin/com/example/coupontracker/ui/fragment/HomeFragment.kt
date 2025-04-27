package com.example.coupontracker.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.coupontracker.databinding.FragmentHomeBinding
import com.example.coupontracker.ui.adapter.CouponAdapter
import com.example.coupontracker.ui.viewmodel.CouponViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CouponViewModel by viewModels()
    private lateinit var adapter: CouponAdapter
    
    companion object {
        private const val MISTRAL_API_URL = "https://console.mistral.ai/api-keys/"
        private const val TAG = "HomeFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            Log.d(TAG, "onCreateView started")
            _binding = FragmentHomeBinding.inflate(inflater, container, false)
            Log.d(TAG, "onCreateView completed successfully")
            return binding.root
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreateView", e)
            // Create a simple view as fallback
            return View(context)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
            super.onViewCreated(view, savedInstanceState)
            Log.d(TAG, "onViewCreated started")
            setupRecyclerView()
            setupClickListeners()
            observeViewModel()
            Log.d(TAG, "onViewCreated completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated", e)
        }
    }

    private fun setupRecyclerView() {
        try {
            Log.d(TAG, "Setting up RecyclerView")
            adapter = CouponAdapter(
                onItemClick = { couponId ->
                    findNavController().navigate(
                        HomeFragmentDirections.actionHomeToDetail(couponId)
                    )
                },
                onCopyCodeClick = { code ->
                    copyToClipboard(code)
                }
            )

            binding.couponsRecyclerView.apply {
                this.layoutManager = LinearLayoutManager(requireContext())
                adapter = this@HomeFragment.adapter
            }
            Log.d(TAG, "RecyclerView setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up RecyclerView", e)
        }
    }

    private fun setupClickListeners() {
        try {
            Log.d(TAG, "Setting up click listeners")
            binding.addCouponFab.setOnClickListener {
                findNavController().navigate(
                    HomeFragmentDirections.actionHomeToAdd()
                )
            }
            
            // Setup Mistral API key button
            binding.getMistralApiKeyButton.setOnClickListener {
                openMistralApiWebsite()
            }

            // Setup search functionality
            binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearchQuery(newText ?: "")
                    return true
                }
            })
            Log.d(TAG, "Click listeners setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up click listeners", e)
        }
    }
    
    private fun openMistralApiWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MISTRAL_API_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Mistral API website", e)
            Toast.makeText(
                requireContext(),
                "Could not open browser. Please visit $MISTRAL_API_URL manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun observeViewModel() {
        try {
            Log.d(TAG, "Setting up ViewModel observation")
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.coupons.collect { coupons ->
                        adapter.submitList(coupons)
                    }
                }
            }
            Log.d(TAG, "ViewModel observation setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up ViewModel observation", e)
        }
    }

    private fun copyToClipboard(code: String) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Redeem Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 