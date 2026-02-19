package com.example.voicerec

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.voicerec.service.RecordingService
import com.example.voicerec.ui.recordings.RecordingsViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 主Activity
 */
class MainActivity : AppCompatActivity() {
    private lateinit var navController: NavController

    private val recordingsViewModel: RecordingsViewModel by viewModels()

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            serviceBound = true
            updateServiceStatus()
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
            startRecordingService()
        } else {
            showPermissionRationale()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // 通知权限不是必需的，但建议开启
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupNavigation()
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // 绑定服务以获取状态
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 监听服务状态变化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val filter = android.content.IntentFilter(RecordingService.ACTION_UPDATE)
            registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            val filter = android.content.IntentFilter(RecordingService.ACTION_UPDATE)
            registerReceiver(serviceStatusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
    }

    private fun checkAndRequestPermissions() {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要录音权限")
            .setMessage("此应用需要录音权限才能检测声音并录音。请在设置中开启权限。")
            .setPositiveButton("去设置") { _, _ ->
                // 打开应用设置
                val intent = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri = android.net.Uri.fromParts("package", packageName, null)
                startActivity(Intent(intent, uri))
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateServiceStatus() {
        val isRunning = recordingService?.getCurrentState() != RecordingService.STATE_IDLE
        recordingsViewModel.updateServiceState(isRunning)
    }

    // 导航方法
    fun navigateToPlayer(recordingId: Long) {
        navController.navigate(
            R.id.action_recordings_to_player,
            Bundle().apply { putLong("recordingId", recordingId) }
        )
    }

    fun navigateToSettings() {
        navController.navigate(R.id.action_recordings_to_settings)
    }

    fun navigateBack() {
        navController.navigateUp()
    }

    // 广播接收器，接收服务状态更新
    private val serviceStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            intent?.getIntExtra("state", RecordingService.STATE_IDLE)?.let { state ->
                val isRunning = state != RecordingService.STATE_IDLE
                recordingsViewModel.updateServiceState(isRunning)
            }
        }
    }
}
