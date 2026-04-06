package com.bongbee.apkurl

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private lateinit var selectedAppIcon: ImageView
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

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        selectedAppIcon = findViewById(R.id.selectedAppIcon)
        selectedAppText = findViewById(R.id.selectedAppText)
        statusText = findViewById(R.id.statusText)
        logsText = findViewById(R.id.logsText)

        findViewById<View>(R.id.pickAppButton).setOnClickListener { showAppPicker() }
        findViewById<View>(R.id.launchAppButton).setOnClickListener { launchSelectedApp() }
        findViewById<View>(R.id.startCaptureButton).setOnClickListener { requestVpnAndStart() }
        findViewById<View>(R.id.stopCaptureButton).setOnClickListener { stopCapture() }
        findViewById<View>(R.id.clearLogButton).setOnClickListener {
            UrlLogStore.clear(this)
            renderLogs()
            statusText.text = getString(R.string.status_logs_cleared)
        }

        requestNotificationPermissionIfNeeded()
        updateSelectedAppLabel()
        renderLogs()
        silentUpdateCheck()
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

    // ── Silent update check on start ──────────────────────────────────────────

    private fun silentUpdateCheck() {
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.checkForUpdates() ?: return@launch
            if (UpdateChecker.isUpdateAvailable(this@MainActivity, updateInfo.versionTag)) {
                UpdateChecker.showUpdateDialog(this@MainActivity, updateInfo)
            }
        }
    }

    // ── App picker with icons ─────────────────────────────────────────────────

    private fun showAppPicker() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_app_title)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(0, 8, 0, 8)
        }

        dialog.setView(recycler)
        dialog.show()

        lifecycleScope.launch {
            statusText.text = getString(R.string.loading_apps)
            val appItems = withContext(Dispatchers.Default) { loadAllApps() }
            statusText.text = getString(R.string.status_idle)

            if (appItems.isEmpty()) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            recycler.adapter = AppPickerAdapter(appItems) { selected ->
                dialog.dismiss()
                prefs.edit().putString(KEY_SELECTED_PACKAGE, selected.packageName).apply()
                updateSelectedAppLabel()
                statusText.text = getString(R.string.status_app_selected, selected.label)
            }
        }
    }

    private fun loadAllApps(): List<AppItem> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)

        return activities
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { buildAppItem(it) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    inner class AppPickerAdapter(
        private val items: List<AppItem>,
        private val onPick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val pkg: TextView = view.findViewById(R.id.appPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_picker, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.label
            holder.pkg.text = item.packageName
            holder.itemView.setOnClickListener { onPick(item) }
        }

        override fun getItemCount() = items.size
    }

    // ── Launch / capture ─────────────────────────────────────────────────────

    private fun launchSelectedApp() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show(); return
        }
        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply(); updateSelectedAppLabel()
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show(); return
        }
        packageManager.getLaunchIntentForPackage(packageName)
            ?.let { startActivity(it) }
            ?: Toast.makeText(this, R.string.cannot_launch_app, Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnAndStart() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show(); return
        }
        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply(); updateSelectedAppLabel()
            Toast.makeText(this, R.string.pick_app_first, Toast.LENGTH_SHORT).show(); return
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
                val pkg = pendingPackageForStart ?: prefs.getString(KEY_SELECTED_PACKAGE, null)
                if (!pkg.isNullOrBlank()) startCapture(pkg)
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
        val name = buildAppItem(packageName)?.label ?: packageName
        statusText.text = getString(R.string.status_capture_started, name)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        statusText.text = getString(R.string.status_capture_stopped)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateSelectedAppLabel() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            selectedAppText.text = getString(R.string.selected_app_none)
            selectedAppIcon.setImageResource(R.mipmap.ic_launcher)
            return
        }
        val appItem = buildAppItem(packageName)
        if (appItem == null) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply()
            selectedAppText.text = getString(R.string.selected_app_none)
            selectedAppIcon.setImageResource(R.mipmap.ic_launcher)
        } else {
            selectedAppText.text = appItem.label
            selectedAppIcon.setImageDrawable(appItem.icon)
        }
    }

    private fun buildAppItem(packageName: String): AppItem? {
        if (!isRealLaunchableApp(packageName)) return null
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(appInfo).toString().trim()
            val launchLabel = packageManager.getLaunchIntentForPackage(packageName)
                ?.let { packageManager.resolveActivity(it, 0) }
                ?.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            val label = when {
                appLabel.isNotBlank() && !looksLikePackageName(appLabel) -> appLabel
                launchLabel.isNotBlank() && !looksLikePackageName(launchLabel) -> launchLabel
                appLabel.isNotBlank() -> appLabel
                launchLabel.isNotBlank() -> launchLabel
                else -> fallbackNameFromPackage(packageName)
            }
            val icon: Drawable? = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
            AppItem(label, packageName, icon)
        } catch (_: PackageManager.NameNotFoundException) { null }
    }

    private fun isRealLaunchableApp(packageName: String): Boolean {
        if (packageName == this.packageName) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.enabled && launchIntent.component != null
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    private fun looksLikePackageName(text: String) = text.contains('.') && text.none { it == ' ' }

    private fun fallbackNameFromPackage(packageName: String) =
        packageName.substringAfterLast('.', packageName)
            .replace('_', ' ').replace('-', ' ').ifBlank { packageName }

    private fun renderLogs() {
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) { logsText.text = getString(R.string.no_logs); return }
        logsText.text = rows.takeLast(200).joinToString("\n\n") { formatLogRow(it) }
    }

    private fun formatLogRow(raw: String): String {
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return raw
        val timestamp = parts[0].toLongOrNull() ?: 0L
        val packageName = parts[1]
        val url = parts[2]
        val timeText = if (timestamp > 0)
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        else "--:--:--"
        return "[$timeText] $packageName\n$url"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), REQUEST_NOTIFICATION_PERMISSION)
        }
    }

    data class AppItem(val label: String, val packageName: String, val icon: Drawable? = null)

    companion object {
        private const val PREFS_NAME = "apkurl_prefs"
        private const val KEY_SELECTED_PACKAGE = "selected_package"
        private const val REQUEST_VPN_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
