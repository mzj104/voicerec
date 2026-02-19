package com.example.voicerec.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicerec.MainActivity
import com.example.voicerec.R
import com.example.voicerec.databinding.FragmentPlayerBinding
import com.example.voicerec.service.RecordingService
import com.example.voicerec.service.WhisperModelManager
import com.example.voicerec.service.WhisperService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放器Fragment
 */
class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayerViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null
    private var updateHandler: Handler? = null
    private var updateRunnable: Runnable? = null

    private var recordingId: Long = 0L

    // 进度更新间隔
    private val UPDATE_INTERVAL = 100L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingId = arguments?.getLong("recordingId") ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeData()
        loadRecording()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.navigateBack()
        }

        // 播放/暂停按钮
        binding.fabPlay.setOnClickListener {
            if (viewModel.isPlaying.value == true) {
                pausePlayer()
            } else {
                playPlayer()
            }
        }

        // 进度条
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 分享按钮
        binding.btnShare.setOnClickListener {
            shareRecording()
        }

        // 删除按钮
        binding.btnDelete.setOnClickListener {
            confirmDelete()
        }

        // 转写按钮
        binding.btnTranscribe.setOnClickListener {
            transcribeRecording()
        }
    }

    private fun observeData() {
        viewModel.currentRecording.observe(viewLifecycleOwner) { recording ->
            recording?.let { updateRecordingInfo(it) }
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            updatePlayButton(isPlaying)
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            binding.seekBar.max = duration
            binding.tvDuration.text = viewModel.formatTime(duration)
        }

        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            binding.tvCurrentPosition.text = viewModel.formatTime(position)
            binding.seekBar.progress = position
        }
    }

    private fun loadRecording() {
        viewModel.loadRecording(recordingId)
    }

    private fun updateRecordingInfo(recording: com.example.voicerec.data.Recording) {
        // 格式化时间
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())

        binding.tvRecordingTime.text = timeFormat.format(Date(recording.timestamp))
        binding.tvRecordingDate.text = dateFormat.format(Date(recording.timestamp))
        binding.tvRecordingDuration.text = viewModel.formatTime((recording.durationMs / 1000).toInt())
        binding.tvRecordingSize.text = viewModel.formatFileSize(recording.fileSizeBytes)

        // 显示转写结果
        updateTranscriptionView(recording)

        // 初始化MediaPlayer
        initPlayer(recording.filePath)
    }

    private fun updateTranscriptionView(recording: com.example.voicerec.data.Recording) {
        if (!recording.transcriptionText.isNullOrEmpty()) {
            binding.tvTranscriptionText.text = recording.transcriptionText
            binding.btnTranscribe.text = "重新转写"

            // 显示转写时间
            recording.transcriptionTime?.let {
                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.tvTranscriptionTime.text = "转写于 ${timeFormat.format(Date(it))}"
                binding.tvTranscriptionTime.visibility = View.VISIBLE
            }
        } else {
            binding.tvTranscriptionText.text = "点击「转文字」按钮开始语音识别"
            binding.btnTranscribe.text = "转文字"
            binding.tvTranscriptionTime.visibility = View.GONE
        }
    }

    private fun transcribeRecording() {
        val recording = viewModel.getCurrentRecording()
        if (recording == null) {
            Toast.makeText(context, "录音信息加载中", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查模型是否已准备好
        val modelManager = WhisperModelManager(requireContext())
        if (!modelManager.isModelReady()) {
            // 显示准备模型对话框
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("准备语音识别模型")
                .setMessage("首次使用需要复制中文语音识别模型 (~800MB) 到应用目录。\n\n是否立即准备？")
                .setPositiveButton("准备") { _, _ ->
                    prepareModelAndTranscribe(recording)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 直接开始转写
        performTranscription(recording)
    }

    private fun prepareModelAndTranscribe(recording: com.example.voicerec.data.Recording) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("准备模型")
            .setMessage("正在复制模型...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val modelManager = WhisperModelManager(requireContext())
            val result = modelManager.copyModelFromAssets { message ->
                activity?.runOnUiThread {
                    progressDialog.setMessage(message)
                }
            }

            progressDialog.dismiss()

            result.fold(
                onSuccess = {
                    Toast.makeText(context, "模型准备完成", Toast.LENGTH_SHORT).show()
                    performTranscription(recording)
                },
                onFailure = { error ->
                    Toast.makeText(context, "失败: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun performTranscription(recording: com.example.voicerec.data.Recording) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音转文字")
            .setMessage("正在处理...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        // 更新UI状态
        binding.tvTranscriptionText.text = "正在识别，请稍候..."
        binding.btnTranscribe.isEnabled = false

        lifecycleScope.launch {
            val whisperService = WhisperService(requireContext())
            val result = whisperService.transcribeAudio(recording.filePath) { message ->
                activity?.runOnUiThread {
                    progressDialog.setMessage(message)
                }
            }
            whisperService.release()

            progressDialog.dismiss()
            binding.btnTranscribe.isEnabled = true

            result.fold(
                onSuccess = { text ->
                    binding.tvTranscriptionText.text = text
                    binding.btnTranscribe.text = "重新转写"

                    // 显示转写时间
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    binding.tvTranscriptionTime.text = "转写于 ${timeFormat.format(Date(System.currentTimeMillis()))}"
                    binding.tvTranscriptionTime.visibility = View.VISIBLE

                    // 更新数据库
                    val updatedRecording = recording.copy(
                        transcriptionText = text,
                        transcriptionTime = System.currentTimeMillis()
                    )
                    viewModel.updateRecording(updatedRecording)

                    Toast.makeText(context, "转写完成", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    binding.tvTranscriptionText.text = "转写失败：${error.message}"
                    Toast.makeText(context, "转写失败: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun initPlayer(filePath: String) {
        try {
            releasePlayer()

            mediaPlayer = MediaPlayer().apply {
                // 使用 FileInputStream 来访问内部存储文件
                val fis = java.io.FileInputStream(filePath)
                val fd = fis.fd
                setDataSource(fd)
                fis.close()
                prepare()
                setOnCompletionListener {
                    viewModel.updatePlayingState(false)
                    viewModel.updateProgress(0)
                }
            }

            // 更新总时长
            val duration = mediaPlayer?.duration ?: 0
            viewModel.updatePlayingState(false)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "无法播放该录音: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPlayer() {
        try {
            mediaPlayer?.start()
            viewModel.updatePlayingState(true)
            startProgressUpdate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pausePlayer() {
        try {
            mediaPlayer?.pause()
            viewModel.updatePlayingState(false)
            stopProgressUpdate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seekTo(position: Int) {
        try {
            mediaPlayer?.seekTo(position * 1000)
            viewModel.updateProgress(position)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seekRelative(offsetMs: Int) {
        try {
            val currentPos = mediaPlayer?.currentPosition ?: 0
            val newPos = ((currentPos + offsetMs) / 1000).toInt()
            val duration = viewModel.duration.value ?: 0
            val clampedPos = newPos.coerceIn(0, duration)
            seekTo(clampedPos)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startProgressUpdate() {
        updateHandler = Handler(Looper.getMainLooper())
        updateRunnable = object : Runnable {
            override fun run() {
                try {
                    val position = ((mediaPlayer?.currentPosition ?: 0) / 1000).toInt()
                    viewModel.updateProgress(position)
                    updateHandler?.postDelayed(this, UPDATE_INTERVAL)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        updateRunnable?.let {
            updateHandler?.post(it)
        }
    }

    private fun stopProgressUpdate() {
        updateRunnable?.let {
            updateHandler?.removeCallbacks(it)
        }
        updateRunnable = null
    }

    private fun releasePlayer() {
        stopProgressUpdate()
        mediaPlayer?.release()
        mediaPlayer = null
        updateHandler = null
    }

    private fun updatePlayButton(isPlaying: Boolean) {
        binding.fabPlay.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun shareRecording() {
        val recording = viewModel.getCurrentRecording()
        recording?.let {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                val file = File(it.filePath)
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                } else {
                    android.net.Uri.fromFile(file)
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享录音"))
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除录音")
            .setMessage("确定要删除这条录音吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteCurrentRecording()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.navigateBack()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        fun newInstance(recordingId: Long): PlayerFragment {
            return PlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong("recordingId", recordingId)
                }
            }
        }
    }
}
