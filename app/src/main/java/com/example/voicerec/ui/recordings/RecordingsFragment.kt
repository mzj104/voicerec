package com.example.voicerec.ui.recordings

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.voicerec.MainActivity
import com.example.voicerec.R
import com.example.voicerec.data.Recording
import com.example.voicerec.databinding.FragmentRecordingsBinding
import com.example.voicerec.service.RecordingService
import com.example.voicerec.service.WhisperModel
import com.example.voicerec.service.WhisperModelManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager

/**
 * 录音列表Fragment
 */
class RecordingsFragment : Fragment() {
    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecordingsViewModel by viewModels()
    private lateinit var adapter: RecordingsAdapter

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    // 转写完成广播接收器
    private val transcriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_TRANSCRIPTION_COMPLETE) {
                val recordingId = intent.getLongExtra("recordingId", -1L)
                if (recordingId > 0) {
                    // 转写完成，刷新列表
                    activity?.runOnUiThread {
                        Toast.makeText(
                            context,
                            "转写完成",
                            Toast.LENGTH_SHORT
                        ).show()
                        // LiveData会自动刷新UI
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            serviceBound = true
            updateServiceUI()

            // 设置状态回调
            recordingService?.statusCallback = { state, message ->
                activity?.runOnUiThread {
                    updateServiceUI()
                    when (state) {
                        RecordingService.STATE_MONITORING ->
                            Toast.makeText(context, "开始监听声音", Toast.LENGTH_SHORT).show()
                        RecordingService.STATE_RECORDING ->
                            Toast.makeText(context, "检测到声音，开始录音", Toast.LENGTH_SHORT).show()
                        RecordingService.STATE_IDLE ->
                            Toast.makeText(context, "服务已停止", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            recordingService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startService()
        } else {
            Toast.makeText(context, "需要录音权限才能使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupClickListeners()
        observeData()
    }

    override fun onStart() {
        super.onStart()
        // 绑定服务
        val intent = Intent(requireContext(), RecordingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 注册转写完成广播 (Android 12+ 需要指定标志)
        val filter = IntentFilter(RecordingService.ACTION_TRANSCRIPTION_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requireContext().registerReceiver(transcriptionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(transcriptionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
        // 注销广播接收器
        try {
            requireContext().unregisterReceiver(transcriptionReceiver)
        } catch (e: Exception) {
            // 忽略未注册的异常
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = RecordingsAdapter(
            viewModel = viewModel,
            onItemClick = { recording ->
                // 跳转到播放页面
                (activity as? MainActivity)?.navigateToPlayer(recording.id)
            },
            onItemLongClick = { recording ->
                showRecordingOptions(recording)
            },
            onDayDelete = { day ->
                confirmDeleteDay(day)
            },
            onTranscribe = { recording ->
                transcribeRecording(recording)
            }
        )

        binding.recyclerRecordings.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.fabService.setOnClickListener {
            if (serviceBound && recordingService?.getCurrentState() != RecordingService.STATE_IDLE) {
                stopService()
            } else {
                checkPermissionAndStart()
            }
        }
    }

    private fun observeData() {
        // 观察录音列表
        viewModel.allRecordings.observe(viewLifecycleOwner) { recordings ->
            updateTreeList(recordings)
            updateEmptyState(recordings.isEmpty())
            // 更新今日统计
            viewModel.updateTodayStats()
        }

        // 观察今日统计
        viewModel.todayStats.observe(viewLifecycleOwner) { stats ->
            stats?.let {
                binding.tvTodayCount.text = it.count.toString()
                binding.tvTodayDuration.text = viewModel.formatDuration(it.durationMs)
            }
        }

        // 观察服务状态
        viewModel.serviceState.observe(viewLifecycleOwner) { state ->
            updateFabState(state is ServiceState.Running)
        }

        // 观察展开状态变化，重新构建列表
        viewModel.expandedDays.observe(viewLifecycleOwner) {
            viewModel.allRecordings.value?.let { recordings ->
                updateTreeList(recordings)
            }
        }

        viewModel.expandedHours.observe(viewLifecycleOwner) {
            viewModel.allRecordings.value?.let { recordings ->
                updateTreeList(recordings)
            }
        }
    }

    private fun updateTreeList(recordings: List<Recording>) {
        val items = mutableListOf<RecordingsAdapter.TreeItem>()

        // 按日期分组
        val groupedByDay = recordings.groupBy { it.dayFolder }

        groupedByDay.forEach { (day, dayRecordings) ->
            val expandedDays = viewModel.expandedDays.value ?: emptySet()
            val dayExpanded = expandedDays.contains(day)

            // 添加日期项
            items.add(
                RecordingsAdapter.TreeItem.DayItem(
                    day = day,
                    count = dayRecordings.size,
                    totalDuration = dayRecordings.sumOf { it.durationMs },
                    expanded = dayExpanded
                )
            )

            // 如果日期展开，添加小时项
            if (dayExpanded) {
                val groupedByHour = dayRecordings.groupBy { it.hourFolder }

                groupedByHour.forEach { (hour, hourRecordings) ->
                    val expandedHours = viewModel.expandedHours.value ?: emptySet()
                    val hourExpanded = expandedHours.contains("${day}_${hour}")

                    items.add(
                        RecordingsAdapter.TreeItem.HourItem(
                            day = day,
                            hour = hour,
                            count = hourRecordings.size,
                            expanded = hourExpanded
                        )
                    )

                    // 如果小时展开，添加录音项
                    if (hourExpanded) {
                        hourRecordings.forEach { recording ->
                            items.add(
                                RecordingsAdapter.TreeItem.RecordingItem(recording)
                            )
                        }
                    }
                }
            }
        }

        adapter.submitList(items)
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerRecordings.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateServiceUI() {
        val isRunning = recordingService?.getCurrentState() != RecordingService.STATE_IDLE
        viewModel.updateServiceState(isRunning)
    }

    private fun updateFabState(isRunning: Boolean) {
        binding.fabService.setImageResource(
            if (isRunning) R.drawable.ic_stop else R.drawable.ic_mic
        )
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            startService()
        }
    }

    private fun startService() {
        val intent = Intent(requireContext(), RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(requireContext(), RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    private fun showRecordingOptions(recording: Recording) {
        val options = arrayOf("播放", "删除", "分享")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("录音操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> (activity as? MainActivity)?.navigateToPlayer(recording.id)
                    1 -> confirmDelete(recording)
                    2 -> shareRecording(recording)
                }
            }
            .show()
    }

    private fun confirmDelete(recording: Recording) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除录音")
            .setMessage("确定要删除这条录音吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteRecording(recording)
                    viewModel.updateTodayStats()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareRecording(recording: Recording) {
        val file = File(recording.filePath)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } else {
            android.net.Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享录音"))
    }

    private fun confirmDeleteDay(day: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除录音")
            .setMessage("确定要删除 ${day} 的所有录音吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteDay(day)
                    viewModel.updateTodayStats()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun transcribeRecording(recording: Recording) {
        // 检查模型是否已下载
        if (!viewModel.isModelDownloaded()) {
            showModelDownloadDialog(recording)
            return
        }

        // 如果已有转录结果，直接显示
        if (!recording.transcriptionText.isNullOrEmpty()) {
            showTranscriptionResult(
                recording.transcriptionText!!,
                recording.transcriptionTime
            )
            return
        }

        // 显示进度对话框
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音转文字")
            .setMessage("正在处理...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = viewModel.transcribeRecording(recording)

            progressDialog.dismiss()

            result.fold(
                onSuccess = { text ->
                    showTranscriptionResult(text, System.currentTimeMillis())
                },
                onFailure = { error ->
                    val message = "识别失败: ${error.message ?: "未知错误"}"
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun showModelDownloadDialog(recording: Recording? = null) {
        lifecycleScope.launch {
            val modelManager = WhisperModelManager(requireContext())
            val selectedModel = modelManager.getSelectedModel()

            val sizeText = when {
                selectedModel.minSizeBytes >= 1024 * 1024 * 1024 -> "~${selectedModel.minSizeBytes / (1024 * 1024 * 1024)}GB"
                selectedModel.minSizeBytes >= 1024 * 1024 -> "~${selectedModel.minSizeBytes / (1024 * 1024)}MB"
                else -> "~${selectedModel.minSizeBytes / 1024}KB"
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("准备语音识别模型")
                .setMessage("首次使用需要复制${selectedModel.displayName}模型 (${sizeText})到应用目录。\n\n是否立即准备？")
                .setPositiveButton("准备") { _, _ ->
                    downloadModel(recording)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun downloadModel(recording: Recording? = null) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("准备模型")
            .setMessage("正在复制模型...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val modelManager = WhisperModelManager(requireContext())
            val selectedModel = modelManager.getSelectedModel()
            val result = modelManager.copyModelFromAssets(selectedModel) { message ->
                activity?.runOnUiThread {
                    progressDialog.setMessage(message)
                }
            }

            progressDialog.dismiss()

            result.fold(
                onSuccess = {
                    Toast.makeText(context, "模型准备完成", Toast.LENGTH_SHORT).show()
                    recording?.let { transcribeRecording(it) }
                },
                onFailure = { error ->
                    Toast.makeText(context, "失败: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun showTranscriptionResult(text: String, timestamp: Long?) {
        val timeText = if (timestamp != null) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            "\n\n转录时间: ${sdf.format(Date(timestamp))}"
        } else {
            ""
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("转写结果")
            .setMessage(text + timeText)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("转写结果", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
