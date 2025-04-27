package com.example.coupontracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.databinding.FragmentSettingsBinding
import com.example.coupontracker.ui.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.sortByDate.setOnClickListener {
            viewModel.updateSortOrder(SortOrder.EXPIRY_DATE)
        }

        binding.sortByName.setOnClickListener {
            viewModel.updateSortOrder(SortOrder.NAME)
        }

        binding.sortByAmount.setOnClickListener {
            viewModel.updateSortOrder(SortOrder.AMOUNT)
        }

        binding.notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateNotificationsEnabled(isChecked)
        }

        binding.darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateDarkModeEnabled(isChecked)
        }

        binding.exportButton.setOnClickListener {
            viewModel.exportData()
        }

        binding.importButton.setOnClickListener {
            viewModel.importData()
        }

        binding.clearDataButton.setOnClickListener {
            viewModel.clearAllData()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    updateUI(settings)
                }
            }
        }
    }

    private fun updateUI(settings: Settings) {
        binding.apply {
            when (settings.sortOrder) {
                SortOrder.EXPIRY_DATE -> sortByDate.isChecked = true
                SortOrder.NAME -> sortByName.isChecked = true
                SortOrder.AMOUNT -> sortByAmount.isChecked = true
            }
            notificationsSwitch.isChecked = settings.notificationsEnabled
            darkModeSwitch.isChecked = settings.darkMode
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 