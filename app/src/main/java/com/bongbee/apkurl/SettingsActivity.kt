package com.bongbee.apkurl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var isKhmer = false

    private lateinit var checkUpdatesBtn: MaterialButton
    private lateinit var updateStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        isKhmer = prefs.getBoolean(KEY_LANG_KH, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        checkUpdatesBtn = findViewById(R.id.settingsCheckUpdatesBtn)
        updateStatusText = findViewById(R.id.settingsUpdateStatus)

        // ── Language toggle ──
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.langToggleGroup)

        toggleGroup.check(if (isKhmer) R.id.langKhBtn else R.id.langEnBtn)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isKhmer = checkedId == R.id.langKhBtn
                prefs.edit().putBoolean(KEY_LANG_KH, isKhmer).apply()
                applyLanguage()
                // Notify overlay if running
                sendBroadcast(Intent(ACTION_LANG_CHANGED).setPackage(packageName))
            }
        }

        // ── Version info ──
        val versionText = findViewById<TextView>(R.id.settingsVersionText)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
            versionText.text = getString(R.string.settings_version_format, versionName, versionCode)
        } catch (_: Exception) {
            versionText.text = getString(R.string.overlay_version_placeholder)
        }

        // ── Check for updates (in-app) ──
        checkUpdatesBtn.setOnClickListener { runUpdateCheck() }

        // ── View on GitHub ──
        findViewById<MaterialButton>(R.id.settingsGithubBtn).setOnClickListener {
            val url = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        applyLanguage()
    }

    // ── In-app update check ─────────────────────────────────────────────────

    private fun setStatus(text: String) {
        updateStatusText.text = text
        updateStatusText.visibility = View.VISIBLE
    }

    private fun runUpdateCheck() {
        lifecycleScope.launch {
            setStatus(getString(R.string.status_checking_updates))
            checkUpdatesBtn.isEnabled = false

            val updateInfo = UpdateChecker.checkForUpdates()
            if (updateInfo == null) {
                setStatus(getString(R.string.status_update_check_failed))
                Toast.makeText(this@SettingsActivity, R.string.status_update_check_failed, Toast.LENGTH_SHORT).show()
                checkUpdatesBtn.isEnabled = true
                return@launch
            }

            val hasUpdate = UpdateChecker.isUpdateAvailable(this@SettingsActivity, updateInfo.versionTag)
            if (hasUpdate) {
                setStatus(getString(R.string.status_update_available, updateInfo.versionTag))
                showInAppUpdateDialog(updateInfo)
            } else {
                setStatus(getString(R.string.status_already_latest))
                Toast.makeText(this@SettingsActivity, R.string.status_already_latest, Toast.LENGTH_SHORT).show()
            }

            checkUpdatesBtn.isEnabled = true
        }
    }

    private fun showInAppUpdateDialog(updateInfo: UpdateChecker.UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, updateInfo.versionTag, updateInfo.releaseNotes))
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.install_update_now) { _, _ ->
                lifecycleScope.launch {
                    setStatus(getString(R.string.status_downloading_update))
                    checkUpdatesBtn.isEnabled = false

                    val apkFile = UpdateChecker.downloadUpdateApk(this@SettingsActivity, updateInfo) { downloaded, total ->
                        val pct = if (total > 0) (downloaded * 100 / total) else -1L
                        runOnUiThread {
                            setStatus(
                                if (pct >= 0)
                                    getString(R.string.status_downloading_progress, pct)
                                else
                                    getString(R.string.status_downloading_update)
                            )
                        }
                    }

                    if (apkFile == null) {
                        setStatus(getString(R.string.status_download_failed))
                        Toast.makeText(this@SettingsActivity, R.string.status_download_failed, Toast.LENGTH_SHORT).show()
                        checkUpdatesBtn.isEnabled = true
                        return@launch
                    }

                    setStatus(getString(R.string.status_installing_update))
                    val launched = UpdateChecker.launchInAppInstaller(this@SettingsActivity, apkFile)
                    setStatus(
                        if (launched) getString(R.string.status_install_prompt_opened)
                        else getString(R.string.status_install_prompt_failed)
                    )
                    if (!launched) {
                        Toast.makeText(this@SettingsActivity, R.string.status_install_prompt_failed, Toast.LENGTH_SHORT).show()
                    }
                    checkUpdatesBtn.isEnabled = true
                }
            }
            .show()
    }

    private fun applyLanguage() {
        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        val langTitle = findViewById<TextView>(R.id.settingsLangTitle)
        val langDesc = findViewById<TextView>(R.id.settingsLangDesc)
        val aboutTitle = findViewById<TextView>(R.id.settingsAboutTitle)
        val appName = findViewById<TextView>(R.id.settingsAppName)
        val checkUpdatesBtn = findViewById<MaterialButton>(R.id.settingsCheckUpdatesBtn)

        if (isKhmer) {
            toolbar.title = getString(R.string.settings_title_kh)
            langTitle.text = getString(R.string.settings_language_kh)
            langDesc.text = getString(R.string.settings_language_desc_kh)
            aboutTitle.text = getString(R.string.settings_about_kh)
            appName.text = getString(R.string.app_name_kh)
            checkUpdatesBtn.text = getString(R.string.settings_check_updates_kh)
        } else {
            toolbar.title = getString(R.string.settings_title)
            langTitle.text = getString(R.string.settings_language)
            langDesc.text = getString(R.string.settings_language_desc)
            aboutTitle.text = getString(R.string.settings_about)
            appName.text = getString(R.string.app_name)
            checkUpdatesBtn.text = getString(R.string.settings_check_updates)
        }
    }

    companion object {
        const val PREFS_NAME = "apkurl_prefs"
        const val KEY_LANG_KH = "overlay_lang_kh"
        const val ACTION_LANG_CHANGED = "com.bongbee.apkurl.ACTION_LANG_CHANGED"
    }
}

