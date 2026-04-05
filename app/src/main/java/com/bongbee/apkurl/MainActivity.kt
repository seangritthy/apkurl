package com.bongbee.apkurl

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private lateinit var selectedAppText: TextView
    private lateinit var statusText: TextView
    private lateinit var logsText: TextView

    private var pendingPackageForStart: String? = null

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CaptureVpnService.ACTION_NEW_RECORD) {
                renderLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedAppText = findViewById(R.id.selectedAppText)
        statusText = findViewById(R.id.statusText)
        logsText = findViewById(R.id.logsText)

        val pickAppButton: Button = findViewById(R.id.pickAppButton)
        val launchAppButton: Button = findViewById(R.id.launchAppButton)
        val startCaptureButton: Button = findViewById(R.id.startCaptureButton)
        val stopCaptureButton: Button = findViewById(R.id.stopCaptureButton)
        val clearLogButton: Button = findViewById(R.id.clearLogButton)
        val checkUpdateButton: Button = findViewById(R.id.checkUpdateButton)

        pickAppButton.setOnClickListener { showAppPicker() }
        launchAppButton.setOnClickListener { launchSelectedApp() }
        startCaptureButton.setOnClickListener { requestVpnAndStart() }
        stopCaptureButton.setOnClickListener { stopCapture() }
        clearLogButton.setOnClickListener {
            UrlLogStore.clear(this)
            renderLogs()
            statusText.text = getString(R.string.status_logs_cleared)
        }
        checkUpdateButton.setOnClickListener { checkForUpdates() }

        requestNotificationPermissionIfNeeded()
        updateSelectedAppLabel()
        renderLogs()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureVpnService.ACTION_NEW_RECORD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            statusText.text = getString(R.string.status_checking_updates)

            val updateInfo = UpdateChecker.checkForUpdates()
            if (updateInfo == null) {
                statusText.text = getString(R.string.status_update_check_failed)
                return@launch
            }

            if (UpdateChecker.isUpdateAvailable(this@MainActivity, updateInfo.versionTag)) {
                statusText.text = getString(R.string.status_update_available, updateInfo.versionTag)
                UpdateChecker.showUpdateDialog(this@MainActivity, updateInfo)
            } else {
                statusText.text = getString(R.string.status_already_latest)
            }
        }
    }

    private fun showAppPicker() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)

        val appItems = activities
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { buildAppItem(it) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

        if (appItems.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = appItems.map { "${it.label} (${it.packageName})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_app_title)
            .setItems(labels) { _, which ->
                val selected = appItems[which]
                prefs.edit().putString(KEY_SELECTED_PACKAGE, selected.packageName).apply()
                updateSelectedAppLabel()
                statusText.text = getString(R.string.status_app_selected, selected.packageName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchSelectedApp() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply()
            updateSelectedAppLabel()
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, R.string.cannot_launch_app, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(launchIntent)
    }

    private fun requestVpnAndStart() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply()
            updateSelectedAppLabel()
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show()
            return
        }

        pendingPackageForStart = packageName
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
        } else {
            startCapture(packageName)
        }
    }

    @Deprecated("Uses startActivityForResult for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                val packageName = pendingPackageForStart ?: prefs.getString(KEY_SELECTED_PACKAGE, null)
                if (!packageName.isNullOrBlank()) {
                    startCapture(packageName)
                }
            } else {
                statusText.text = getString(R.string.status_vpn_denied)
            }
        }
    }

    private fun startCapture(packageName: String) {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_START)
            .putExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE, packageName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }

        statusText.text = getString(R.string.status_capture_started, packageName)
    }

    private fun stopCapture() {
        val intent = Intent(this, CaptureVpnService::class.java)
            .setAction(CaptureVpnService.ACTION_STOP)
        startService(intent)
        statusText.text = getString(R.string.status_capture_stopped)
    }

    private fun updateSelectedAppLabel() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            selectedAppText.text = getString(R.string.selected_app_none)
            return
        }

        val appItem = buildAppItem(packageName)
        selectedAppText.text = if (appItem == null) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply()
            getString(R.string.selected_app_none)
        } else {
            getString(R.string.selected_app_value, "${appItem.label} (${appItem.packageName})")
        }
    }

    private fun buildAppItem(packageName: String): AppItem? {
        if (!isRealLaunchableApp(packageName)) {
            return null
        }

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { packageName }
            AppItem(label, packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isRealLaunchableApp(packageName: String): Boolean {
        if (packageName == this.packageName) {
            return false
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.enabled && launchIntent.component != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun renderLogs() {
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            logsText.text = getString(R.string.no_logs)
            return
        }

        val latest = rows.takeLast(200)
        logsText.text = latest.joinToString(separator = "\n\n") { formatLogRow(it) }
    }

    private fun formatLogRow(raw: String): String {
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) {
            return raw
        }

        val timestamp = parts[0].toLongOrNull() ?: 0L
        val packageName = parts[1]
        val url = parts[2]
        val timeText = if (timestamp > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        } else {
            "--:--:--"
        }

        return "[$timeText] $packageName\n$url"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_NOTIFICATION_PERMISSION)
    }

    data class AppItem(val label: String, val packageName: String)

    companion object {
        private const val PREFS_NAME = "apkurl_prefs"
        private const val KEY_SELECTED_PACKAGE = "selected_package"
        private const val REQUEST_VPN_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}

