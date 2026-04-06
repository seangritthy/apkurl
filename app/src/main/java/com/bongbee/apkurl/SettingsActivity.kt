package com.bongbee.apkurl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var isKhmer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        isKhmer = prefs.getBoolean(KEY_LANG_KH, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // ── Language toggle ──
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.langToggleGroup)
        val enBtn = findViewById<MaterialButton>(R.id.langEnBtn)
        val khBtn = findViewById<MaterialButton>(R.id.langKhBtn)

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

        // ── Check for updates ──
        findViewById<MaterialButton>(R.id.settingsCheckUpdatesBtn).setOnClickListener {
            val url = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // ── View on GitHub ──
        findViewById<MaterialButton>(R.id.settingsGithubBtn).setOnClickListener {
            val url = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        applyLanguage()
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

