package com.bongbee.apkurl

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var isKhmer = false

    private lateinit var selectedAppIcon: ImageView
    private lateinit var selectedAppText: TextView
    private lateinit var statusText: TextView
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logsEmptyText: TextView
    private lateinit var checkUpdatesButton: View

    // Button references for language switching
    private lateinit var pickAppButton: MaterialButton
    private lateinit var pickFromScreenButton: MaterialButton
    private lateinit var launchAppButton: MaterialButton
    private lateinit var startCaptureButton: MaterialButton
    private lateinit var stopCaptureButton: MaterialButton
    private lateinit var overlayButton: MaterialButton
    private lateinit var exportLogButton: MaterialButton
    private lateinit var clearLogButton: MaterialButton
    private lateinit var logTitleText: TextView

    private var pendingPackageForStart: String? = null
    private var pendingPickFromScreen: Boolean = false

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val success = UrlLogStore.exportToTxt(this, uri)
            if (success) {
                statusText.text = s(R.string.export_success, R.string.export_success_kh)
                Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = getString(R.string.export_failed)
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

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

        isKhmer = prefs.getBoolean("overlay_lang_kh", false)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        selectedAppIcon = findViewById(R.id.selectedAppIcon)
        selectedAppText = findViewById(R.id.selectedAppText)
        statusText = findViewById(R.id.statusText)
        logsRecyclerView = findViewById(R.id.logsRecyclerView)
        logsEmptyText = findViewById(R.id.logsEmptyText)
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton)

        pickAppButton = findViewById(R.id.pickAppButton)
        pickFromScreenButton = findViewById(R.id.pickFromScreenButton)
        launchAppButton = findViewById(R.id.launchAppButton)
        startCaptureButton = findViewById(R.id.startCaptureButton)
        stopCaptureButton = findViewById(R.id.stopCaptureButton)
        overlayButton = findViewById(R.id.overlayButton)
        exportLogButton = findViewById(R.id.exportLogButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        logTitleText = findViewById(R.id.logTitleText)

        logsRecyclerView.layoutManager = LinearLayoutManager(this)

        pickAppButton.setOnClickListener { showAppPicker() }
        pickFromScreenButton.setOnClickListener { pickFromScreen() }
        launchAppButton.setOnClickListener { launchSelectedApp() }
        startCaptureButton.setOnClickListener { requestVpnAndStart() }
        stopCaptureButton.setOnClickListener { stopCapture() }
        overlayButton.setOnClickListener { showOverlay() }
        checkUpdatesButton.setOnClickListener { runUpdateCheck(manual = true) }
        exportLogButton.setOnClickListener { exportLogs() }
        clearLogButton.setOnClickListener {
            UrlLogStore.clear(this)
            renderLogs()
            statusText.text = s(R.string.status_logs_cleared, R.string.status_logs_cleared_kh)
        }

        applyLanguage()
        requestNotificationPermissionIfNeeded()
        updateSelectedAppLabel()
        renderLogs()
        silentUpdateCheck()
    }

    override fun onResume() {
        super.onResume()
        // Re-read language in case changed from Settings
        val newKhmer = prefs.getBoolean("overlay_lang_kh", false)
        if (newKhmer != isKhmer) {
            isKhmer = newKhmer
            applyLanguage()
            invalidateOptionsMenu()
        }
        if (pendingPickFromScreen && hasUsageAccessPermission()) {
            pendingPickFromScreen = false
            resolveAndSelectForegroundApp()
        }
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

    // ── Menu ───────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Language ────────────────────────────────────────────────────────────────

    /** Helper: return EN or KH string based on current language */
    private fun s(enId: Int, khId: Int): String =
        if (isKhmer) getString(khId) else getString(enId)

    private fun applyLanguage() {
        supportActionBar?.title = s(R.string.app_name, R.string.app_name_kh)
        pickAppButton.text = s(R.string.pick_app_short, R.string.pick_app_short_kh)
        pickFromScreenButton.text = s(R.string.pick_from_screen_short, R.string.pick_from_screen_short_kh)
        launchAppButton.text = s(R.string.launch_selected_app, R.string.launch_selected_app_kh)
        startCaptureButton.text = s(R.string.start_capture, R.string.start_capture_kh)
        stopCaptureButton.text = s(R.string.stop_capture, R.string.stop_capture_kh)
        overlayButton.text = s(R.string.show_overlay, R.string.show_overlay_kh)
        (checkUpdatesButton as? MaterialButton)?.text = s(R.string.check_updates, R.string.check_updates_kh)
        exportLogButton.text = s(R.string.export_logs, R.string.export_logs_kh)
        clearLogButton.text = s(R.string.clear_logs, R.string.clear_logs_kh)
        logTitleText.text = s(R.string.log_title, R.string.log_title_kh)
        statusText.text = s(R.string.status_idle, R.string.status_idle_kh)
        updateSelectedAppLabel()
    }

    // ── Silent update check on start ──────────────────────────────────────────

    private fun silentUpdateCheck() {
        runUpdateCheck(manual = false)
    }

    private fun runUpdateCheck(manual: Boolean) {
        lifecycleScope.launch {
            if (manual) {
                statusText.text = getString(R.string.status_checking_updates)
                checkUpdatesButton.isEnabled = false
            }

            val updateInfo = UpdateChecker.checkForUpdates()
            if (updateInfo == null) {
                if (manual) {
                    statusText.text = getString(R.string.status_update_check_failed)
                    Toast.makeText(this@MainActivity, R.string.status_update_check_failed, Toast.LENGTH_SHORT).show()
                    checkUpdatesButton.isEnabled = true
                }
                return@launch
            }

            val hasUpdate = UpdateChecker.isUpdateAvailable(this@MainActivity, updateInfo.versionTag)
            if (hasUpdate) {
                statusText.text = getString(R.string.status_update_available, updateInfo.versionTag)
                showInAppUpdateDialog(updateInfo)
            } else if (manual) {
                statusText.text = getString(R.string.status_already_latest)
                Toast.makeText(this@MainActivity, R.string.status_already_latest, Toast.LENGTH_SHORT).show()
            }

            if (manual) {
                checkUpdatesButton.isEnabled = true
            }
        }
    }

    private fun showInAppUpdateDialog(updateInfo: UpdateChecker.UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, updateInfo.versionTag, updateInfo.releaseNotes))
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.install_update_now) { _, _ ->
                lifecycleScope.launch {
                    statusText.text = getString(R.string.status_downloading_update)
                    checkUpdatesButton.isEnabled = false

                    val apkFile = UpdateChecker.downloadUpdateApk(this@MainActivity, updateInfo) { downloaded, total ->
                        val pct = if (total > 0) (downloaded * 100 / total) else -1L
                        runOnUiThread {
                            statusText.text = if (pct >= 0)
                                getString(R.string.status_downloading_progress, pct)
                            else
                                getString(R.string.status_downloading_update)
                        }
                    }

                    if (apkFile == null) {
                        statusText.text = getString(R.string.status_download_failed)
                        Toast.makeText(this@MainActivity, R.string.status_download_failed, Toast.LENGTH_SHORT).show()
                        checkUpdatesButton.isEnabled = true
                        return@launch
                    }

                    statusText.text = getString(R.string.status_installing_update)
                    val launched = UpdateChecker.launchInAppInstaller(this@MainActivity, apkFile)
                    statusText.text = if (launched) {
                        getString(R.string.status_install_prompt_opened)
                    } else {
                        getString(R.string.status_install_prompt_failed)
                    }
                    if (!launched) {
                        Toast.makeText(this@MainActivity, R.string.status_install_prompt_failed, Toast.LENGTH_SHORT).show()
                    }
                    checkUpdatesButton.isEnabled = true
                }
            }
            .show()
    }

    // ── App picker with icons ─────────────────────────────────────────────────

    private fun showAppPicker() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(s(R.string.pick_app_title, R.string.pick_app_title_kh))
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(0, 8, 0, 8)
        }

        dialog.setView(recycler)
        dialog.show()

        lifecycleScope.launch {
            statusText.text = s(R.string.loading_apps, R.string.loading_apps_kh)
            val appItems = withContext(Dispatchers.Default) { loadAllApps() }
            statusText.text = s(R.string.status_idle, R.string.status_idle_kh)

            if (appItems.isEmpty()) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            recycler.adapter = AppPickerAdapter(appItems) { selected ->
                dialog.dismiss()
                prefs.edit().putString(KEY_SELECTED_PACKAGE, selected.packageName).apply()
                updateSelectedAppLabel()
                statusText.text = if (isKhmer) getString(R.string.status_app_selected_kh, selected.label)
                                  else getString(R.string.status_app_selected, selected.label)
            }
        }
    }

    private fun pickFromScreen() {
        if (!hasUsageAccessPermission()) {
            pendingPickFromScreen = true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pick_from_screen_title)
                .setMessage(R.string.pick_from_screen_permission_message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingPickFromScreen = false }
                .setPositiveButton(R.string.open_usage_access_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .show()
            return
        }
        resolveAndSelectForegroundApp()
    }

    private fun resolveAndSelectForegroundApp() {
        lifecycleScope.launch {
            statusText.text = getString(R.string.status_detecting_foreground_app)
            val packageName = withContext(Dispatchers.Default) { findRecentForegroundPackage() }
            if (packageName.isNullOrBlank()) {
                statusText.text = s(R.string.status_idle, R.string.status_idle_kh)
                Toast.makeText(this@MainActivity, R.string.foreground_app_not_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val appItem = withContext(Dispatchers.Default) { buildAppItem(packageName) }
            if (appItem == null) {
                statusText.text = s(R.string.status_idle, R.string.status_idle_kh)
                Toast.makeText(this@MainActivity, R.string.foreground_app_not_launchable, Toast.LENGTH_SHORT).show()
                return@launch
            }

            prefs.edit().putString(KEY_SELECTED_PACKAGE, appItem.packageName).apply()
            updateSelectedAppLabel()
            statusText.text = if (isKhmer) getString(R.string.status_app_selected_kh, appItem.label)
                              else getString(R.string.status_app_selected, appItem.label)
            Toast.makeText(this@MainActivity, getString(R.string.selected_from_screen, appItem.label), Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun findRecentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - FOREGROUND_LOOKBACK_MS
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()

        var latestPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == packageName) continue
            if (isForegroundEvent(event.eventType) && !isLikelyHomePackage(event.packageName)) {
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    @Suppress("DEPRECATION")
    private fun isForegroundEvent(type: Int): Boolean {
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun isLikelyHomePackage(candidatePackage: String): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeResolve = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return candidatePackage == homeResolve?.activityInfo?.packageName
    }

    private fun loadAllApps(): List<AppItem> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        }

        return activities
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == this.packageName) return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val safeLabel = if (label.isNotBlank()) label else fallbackNameFromPackage(packageName)
                val icon: Drawable? = try {
                    info.loadIcon(packageManager) ?: packageManager.getApplicationIcon(packageName)
                } catch (_: Exception) {
                    null
                }
                AppItem(safeLabel, packageName, icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
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
            Toast.makeText(this, s(R.string.pick_app_first, R.string.pick_app_first_kh), Toast.LENGTH_SHORT).show(); return
        }
        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply(); updateSelectedAppLabel()
            Toast.makeText(this, s(R.string.pick_app_first, R.string.pick_app_first_kh), Toast.LENGTH_SHORT).show(); return
        }
        getLaunchIntentForPackageSafe(packageName)
            ?.let { startActivity(it) }
            ?: Toast.makeText(this, R.string.cannot_launch_app, Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnAndStart() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            Toast.makeText(this, s(R.string.pick_app_first, R.string.pick_app_first_kh), Toast.LENGTH_SHORT).show(); return
        }
        if (!isRealLaunchableApp(packageName)) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply(); updateSelectedAppLabel()
            Toast.makeText(this, s(R.string.pick_app_first, R.string.pick_app_first_kh), Toast.LENGTH_SHORT).show(); return
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
        statusText.text = if (isKhmer) getString(R.string.status_capture_started_kh, name)
                          else getString(R.string.status_capture_started, name)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        statusText.text = s(R.string.status_capture_stopped, R.string.status_capture_stopped_kh)
    }

    private fun exportLogs() {
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            Toast.makeText(this, s(R.string.export_empty, R.string.export_empty_kh), Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        exportFileLauncher.launch("apkurl_${timestamp}.txt")
    }

    private fun showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }
    }

    // ── URL Type Categorization ────────────────────────────────────────────────

    enum class UrlType(val label: String, val color: Int) {
        M3U8("M3U8", 0xFF4CAF50.toInt()),     // Green
        MEDIA("MEDIA", 0xFF2196F3.toInt()),    // Blue
        GENERAL("URL", 0xFF78909C.toInt())     // Blue-grey
    }

    data class LogEntry(
        val timestamp: Long,
        val packageName: String,
        val url: String,
        val type: UrlType
    )

    private fun categorizeUrl(url: String): UrlType {
        val lower = url.lowercase()
        if (lower.contains(".m3u8")) return UrlType.M3U8
        val mediaExts = listOf(
            ".ts", ".mp4", ".mp3", ".mpd", ".m4s", ".aac", ".flv", ".m4a", ".m4v",
            ".webm", ".f4v", ".f4m", ".mov", ".avi", ".mkv", ".wav", ".ogg", ".m3u",
            ".ismv", ".isma", ".vtt", ".srt"
        )
        val mediaKeywords = listOf(
            "/video/", "/audio/", "/stream", "/live/", "/hls/", "/dash/",
            "/media/", "/play/", "/vod/", "videoplayback", "googlevideo.com"
        )
        if (mediaExts.any { lower.contains("$it?") || lower.contains("$it&") || lower.endsWith(it) }) return UrlType.MEDIA
        if (mediaKeywords.any { lower.contains(it) }) return UrlType.MEDIA
        return UrlType.GENERAL
    }

    private fun parseLogRow(raw: String): LogEntry? {
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return null
        val timestamp = parts[0].toLongOrNull() ?: 0L
        val packageName = parts[1]
        val url = parts[2]
        return LogEntry(timestamp, packageName, url, categorizeUrl(url))
    }

    // ── Log RecyclerView Adapter ──────────────────────────────────────────────

    inner class LogAdapter(
        private val entries: List<LogEntry>,
        private val onPlay: (LogEntry) -> Unit
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val badge: TextView = view.findViewById(R.id.logTypeBadge)
            val url: TextView = view.findViewById(R.id.logUrl)
            val meta: TextView = view.findViewById(R.id.logMeta)
            val playBtn: ImageView = view.findViewById(R.id.logPlayBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]

            // Set badge
            holder.badge.text = entry.type.label
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dpToPx(4).toFloat()
            bg.setColor(entry.type.color)
            holder.badge.background = bg

            // Set URL text
            holder.url.text = entry.url

            // Set meta (time + package)
            val timeText = if (entry.timestamp > 0) timeFormat.format(Date(entry.timestamp)) else "--:--:--"
            holder.meta.text = "[$timeText] ${entry.packageName}"

            // Play button
            holder.playBtn.setOnClickListener { onPlay(entry) }

            // Long-press to copy URL
            holder.itemView.setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", entry.url))
                Toast.makeText(this@MainActivity, "URL copied", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun getItemCount() = entries.size
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ── Play URL ──────────────────────────────────────────────────────────────

    private fun playUrl(entry: LogEntry) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, entry.url)
            .putExtra(PlayerActivity.EXTRA_URL_TYPE, entry.type.label)
        startActivity(intent)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateSelectedAppLabel() {
        val packageName = prefs.getString(KEY_SELECTED_PACKAGE, null)
        if (packageName.isNullOrBlank()) {
            selectedAppText.text = s(R.string.selected_app_none, R.string.selected_app_none_kh)
            selectedAppIcon.setImageResource(R.mipmap.ic_launcher)
            return
        }
        val appItem = buildAppItem(packageName)
        if (appItem == null) {
            prefs.edit().remove(KEY_SELECTED_PACKAGE).apply()
            selectedAppText.text = s(R.string.selected_app_none, R.string.selected_app_none_kh)
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
        val launchIntent = getLaunchIntentForPackageSafe(packageName) ?: return false
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.enabled && launchIntent.component != null
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    private fun getLaunchIntentForPackageSafe(packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        val resolved = matches.firstOrNull()?.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(resolved.packageName, resolved.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun looksLikePackageName(text: String) = text.contains('.') && text.none { it == ' ' }

    private fun fallbackNameFromPackage(packageName: String) =
        packageName.substringAfterLast('.', packageName)
            .replace('_', ' ').replace('-', ' ').ifBlank { packageName }

    private fun renderLogs() {
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            logsEmptyText.visibility = View.VISIBLE
            logsEmptyText.text = s(R.string.no_logs, R.string.no_logs_kh)
            logsRecyclerView.visibility = View.GONE
            return
        }
        logsEmptyText.visibility = View.GONE
        logsRecyclerView.visibility = View.VISIBLE
        val entries = rows.takeLast(200).mapNotNull { parseLogRow(it) }.reversed()
        logsRecyclerView.adapter = LogAdapter(entries) { entry -> playUrl(entry) }
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
        private const val FOREGROUND_LOOKBACK_MS = 20_000L
    }
}
