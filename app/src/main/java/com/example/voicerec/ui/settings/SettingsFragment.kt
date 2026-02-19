package com.example.voicerec.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicerec.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

/**
 * 设置Fragment
 */
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
        setupUI()
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        // 音量阈值滑块
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolumeValue.text = progress.toString()
                if (fromUser) {
                    lifecycleScope.launch {
                        viewModel.updateVolumeThreshold(progress)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 静音超时按钮组
        binding.btnSilence5.setOnClickListener {
            updateSilenceTimeout(5)
        }

        binding.btnSilence10.setOnClickListener {
            updateSilenceTimeout(10)
        }

        binding.btnSilence30.setOnClickListener {
            updateSilenceTimeout(30)
        }
    }

    private fun observeData() {
        // 观察音量阈值
        viewModel.volumeThreshold.observe(viewLifecycleOwner) { threshold ->
            binding.seekVolume.progress = threshold
            binding.tvVolumeValue.text = threshold.toString()
        }

        // 观察静音超时
        viewModel.silenceTimeout.observe(viewLifecycleOwner) { timeout ->
            updateSilenceTimeoutUI(timeout)
        }

        // 更新存储信息
        updateStorageInfo()
    }

    private fun updateSilenceTimeout(seconds: Int) {
        lifecycleScope.launch {
            viewModel.updateSilenceTimeout(seconds)
            updateSilenceTimeoutUI(seconds)
        }
    }

    private fun updateSilenceTimeoutUI(seconds: Int) {
        when (seconds) {
            5 -> binding.toggleSilence.check(binding.btnSilence5.id)
            10 -> binding.toggleSilence.check(binding.btnSilence10.id)
            30 -> binding.toggleSilence.check(binding.btnSilence30.id)
        }
    }

    private fun updateStorageInfo() {
        binding.tvStorageUsed.text = viewModel.getStorageUsed()
        // 简化处理，设置固定进度
        binding.progressStorage.progress = 25
    }
}
