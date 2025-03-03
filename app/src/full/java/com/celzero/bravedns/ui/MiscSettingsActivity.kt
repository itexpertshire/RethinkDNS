/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.Manifest
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.R
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityMiscSettingsBinding
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import org.koin.android.ext.android.inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MiscSettingsActivity : AppCompatActivity(R.layout.activity_misc_settings) {
    private val b by viewBinding(ActivityMiscSettingsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        registerForActivityResult()
        initView()
        setupClickListeners()
    }

    private fun initView() {

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
        }

        // enable logs
        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        // check for app updates
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate

        // for app locale (default system/user selected locale)
        if (isAtleastT()) {
            val currentAppLocales: LocaleList =
                getSystemService(LocaleManager::class.java).applicationLocales
            b.settingsLocaleDesc.text =
                currentAppLocales[0]?.displayName ?: getString(R.string.settings_locale_desc)
        } else {
            b.settingsLocaleDesc.text =
                AppCompatDelegate.getApplicationLocales().get(0)?.displayName
                    ?: getString(R.string.settings_locale_desc)
        }
        // biometric authentication
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            b.settingsBiometricSwitch.isChecked = persistentState.biometricAuth
        } else {
            b.settingsBiometricRl.visibility = View.GONE
        }

        displayAppThemeUi()
        displayGoLoggerUi()
        displayNotificationActionUi()
        displayPcapUi()
    }

    private fun displayNotificationActionUi() {
        b.settingsActivityNotificationRl.isEnabled = true
        when (
            NotificationActionType.getNotificationActionType(persistentState.notificationActionType)
        ) {
            NotificationActionType.PAUSE_STOP -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc1)
                    )
            }
            NotificationActionType.DNS_FIREWALL -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc2)
                    )
            }
            NotificationActionType.NONE -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc3)
                    )
            }
        }
    }

    private fun displayPcapUi() {
        b.settingsActivityPcapRl.isEnabled = true
        when (PcapMode.getPcapType(persistentState.pcapMode)) {
            PcapMode.NONE -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_1)
            }
            PcapMode.LOGCAT -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_2)
            }
            PcapMode.EXTERNAL_FILE -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_3)
            }
        }
    }

    private fun displayAppThemeUi() {
        b.settingsActivityThemeRl.isEnabled = true
        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_1)
                    )
            }
            Themes.LIGHT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_2)
                    )
            }
            Themes.DARK.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_3)
                    )
            }
            else -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_4)
                    )
            }
        }
    }

    private fun displayGoLoggerUi() {
        if (DEBUG) {
            b.settingsGoLogRl.visibility = View.VISIBLE
        } else {
            b.settingsGoLogRl.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        b.settingsActivityEnableLogsRl.setOnClickListener {
            b.settingsActivityEnableLogsSwitch.isChecked =
                !b.settingsActivityEnableLogsSwitch.isChecked
        }

        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean
            ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityCheckUpdateRl.setOnClickListener {
            b.settingsActivityCheckUpdateSwitch.isChecked =
                !b.settingsActivityCheckUpdateSwitch.isChecked
        }

        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityThemeRl.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityThemeRl)
            showThemeDialog()
        }

        b.settingsGoLogRl.setOnClickListener {
            enableAfterDelay(500, b.settingsGoLogRl)
            showGoLoggerDialog()
        }

        // Ideally this property should be part of VPN category / section.
        // As of now the VPN section will be disabled when the
        // VPN is in lockdown mode.
        // TODO - Find a way to place this property to place in correct section.
        b.settingsActivityNotificationRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityNotificationRl)
            showNotificationActionDialog()
        }

        b.settingsActivityPcapRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityPcapRl)
            showPcapOptionsDialog()
        }

        b.settingsActivityImportExportRl.setOnClickListener { invokeImportExport() }

        b.settingsActivityAppNotificationSwitch.setOnClickListener {
            b.settingsActivityAppNotificationSwitch.isChecked =
                !b.settingsActivityAppNotificationSwitch.isChecked
            invokeNotificationPermission()
        }

        b.settingsLocaleRl.setOnClickListener { invokeChangeLocaleDialog() }

        b.settingsBiometricRl.setOnClickListener {
            b.settingsBiometricSwitch.isChecked = !b.settingsBiometricSwitch.isChecked
        }

        b.settingsBiometricSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean
            ->
            persistentState.biometricAuth = checked
        }
    }

    private fun invokeChangeLocaleDialog() {
        val alertBuilder = AlertDialog.Builder(this)
        alertBuilder.setTitle(getString(R.string.settings_locale_dialog_title))
        val languages = getLocaleEntries()
        val items = languages.keys.toTypedArray()
        val selectedKey = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
        var checkedItem = 0
        languages.values.forEachIndexed { index, s ->
            if (s == selectedKey) {
                checkedItem = index
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val item = items[which]
            // https://developer.android.com/guide/topics/resources/app-languages#app-language-settings
            val locale = languages.getOrDefault(item, "en-US")
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
        }
        alertBuilder.setNeutralButton(getString(R.string.settings_locale_dialog_neutral)) {
            dialog,
            _ ->
            dialog.dismiss()
            openActionViewIntent(getString(R.string.about_translate_link).toUri())
        }
        alertBuilder.create().show()
    }

    private fun openActionViewIntent(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.intent_launch_error, intent.data),
                Toast.LENGTH_SHORT
            )
            Log.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    // read the list of supported languages from locale_config.xml
    private fun getLocalesFromLocaleConfig(): LocaleListCompat {
        val tagsList = mutableListOf<CharSequence>()
        try {
            val xpp: XmlPullParser = resources.getXml(R.xml.locale_config)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG) {
                    if (xpp.name == "locale") {
                        tagsList.add(xpp.getAttributeValue(0))
                    }
                }
                xpp.next()
            }
        } catch (e: XmlPullParserException) {
            Log.e(LOG_TAG_UI, "error parsing locale_config.xml", e)
        } catch (e: IOException) {
            Log.e(LOG_TAG_UI, "error parsing locale_config.xml", e)
        }

        return LocaleListCompat.forLanguageTags(tagsList.joinToString(","))
    }

    private fun getLocaleEntries(): Map<String, String> {
        val localeList = getLocalesFromLocaleConfig()
        val map = mutableMapOf<String, String>()

        for (a in 0 until localeList.size()) {
            localeList[a].let { it?.let { it1 -> map.put(it1.displayName, it1.toLanguageTag()) } }
        }
        return map
    }

    private fun invokeImportExport() {
        val bottomSheetFragment = BackupRestoreBottomSheetFragment()
        bottomSheetFragment.show(this.supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = AlertDialog.Builder(this)
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4)
            )
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            when (which) {
                Themes.SYSTEM_DEFAULT.id -> {
                    if (isDarkThemeOn()) {
                        setThemeRecreate(R.style.AppTheme)
                    } else {
                        setThemeRecreate(R.style.AppThemeWhite)
                    }
                }
                Themes.LIGHT.id -> {
                    setThemeRecreate(R.style.AppThemeWhite)
                }
                Themes.DARK.id -> {
                    setThemeRecreate(R.style.AppTheme)
                }
                Themes.TRUE_BLACK.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlack)
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun showGoLoggerDialog() {
        // show dialog with logger options, change log level in GoVpnAdapter based on selection
        val alertBuilder = AlertDialog.Builder(this)
        alertBuilder.setTitle(getString(R.string.settings_gologger_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_gologger_dialog_option_1),
                getString(R.string.settings_gologger_dialog_option_2),
                getString(R.string.settings_gologger_dialog_option_3),
                getString(R.string.settings_gologger_dialog_option_4),
                getString(R.string.settings_gologger_dialog_option_5)
            )
        val checkedItem = persistentState.goLoggerLevel.toInt()
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.goLoggerLevel.toInt() == which) {
                return@setSingleChoiceItems
            }

            persistentState.goLoggerLevel = which.toLong()
            GoVpnAdapter.setLogLevel(which)
        }
        alertBuilder.create().show()
    }

    private fun setThemeRecreate(theme: Int) {
        setTheme(theme)
        recreate()
    }

    private fun showPcapOptionsDialog() {
        val alertBuilder = AlertDialog.Builder(this)
        alertBuilder.setTitle(getString(R.string.settings_pcap_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_pcap_dialog_option_1),
                getString(R.string.settings_pcap_dialog_option_2),
                getString(R.string.settings_pcap_dialog_option_3)
            )
        val checkedItem = persistentState.pcapMode
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.pcapMode == which) {
                return@setSingleChoiceItems
            }

            when (PcapMode.getPcapType(which)) {
                PcapMode.NONE -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_1)
                    appConfig.setPcap(PcapMode.NONE.id)
                }
                PcapMode.LOGCAT -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_2)
                    appConfig.setPcap(PcapMode.LOGCAT.id, PcapMode.ENABLE_PCAP_LOGCAT)
                }
                PcapMode.EXTERNAL_FILE -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_3)
                    createAndSetPcapFile()
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun showNotificationActionDialog() {
        val alertBuilder = AlertDialog.Builder(this)
        alertBuilder.setTitle(getString(R.string.settings_notification_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_notification_dialog_option_1),
                getString(R.string.settings_notification_dialog_option_2),
                getString(R.string.settings_notification_dialog_option_3)
            )
        val checkedItem = persistentState.notificationActionType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.notificationActionType == which) {
                return@setSingleChoiceItems
            }

            when (NotificationActionType.getNotificationActionType(which)) {
                NotificationActionType.PAUSE_STOP -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc1)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.PAUSE_STOP.action
                }
                NotificationActionType.DNS_FIREWALL -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc2)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.DNS_FIREWALL.action
                }
                NotificationActionType.NONE -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc3)
                        )
                    persistentState.notificationActionType = NotificationActionType.NONE.action
                }
            }
        }
        alertBuilder.create().show()
    }

    override fun onResume() {
        super.onResume()
        // app notification permission android 13
        showEnableNotificationSettingIfNeeded()
    }

    private fun registerForActivityResult() {
        // app notification permission android 13
        if (!isAtleastT()) return

        // Sets up permissions request launcher.
        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) {
                    Log.i(LOG_TAG_VPN, "User allowed notification permission for the app")
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = true
                } else {
                    Log.w(LOG_TAG_VPN, "User rejected notification permission for the app")
                    persistentState.shouldRequestNotificationPermission = false
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = false
                    invokeAndroidNotificationSetting()
                }
            }
    }

    private fun showFileCreationErrorToast() {
        showToastUiCentered(this, getString(R.string.pcap_failure_toast), Toast.LENGTH_SHORT)
    }

    private fun createAndSetPcapFile() {
        try {
            val file = makePcapFile()
            if (file == null) {
                showFileCreationErrorToast()
                return
            }
            // set the file descriptor instead of fd, need to close the file descriptor
            // after tunnel creation
            appConfig.setPcap(PcapMode.EXTERNAL_FILE.id, file.absolutePath)
        } catch (e: Exception) {
            showFileCreationErrorToast()
        }
    }

    private fun makePcapFile(): File? {
        return try {
            val sdf = SimpleDateFormat(BackupHelper.BACKUP_FILE_NAME_DATETIME, Locale.ROOT)
            // create folder in DOWNLOADS
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // create folder in DOWNLOADS/Rethink
            val dir = File(downloadsDir, Constants.PCAP_FOLDER_NAME)
            dir.mkdirs()
            // filename format (Rethink_PCAP_DATE_FORMAT.pcap)
            val pcapFileName: String =
                Constants.PCAP_FILE_NAME_PART + sdf.format(Date()) + Constants.PCAP_FILE_EXTENSION
            val file = File(dir, pcapFileName)
            file
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error creating pcap file ${e.message}", e)
            null
        }
    }

    private fun invokeNotificationPermission() {
        if (!isAtleastT()) {
            // notification permission is needed for version 13 or above
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification already granted
            invokeAndroidNotificationSetting()
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun invokeAndroidNotificationSetting() {
        val packageName = this.packageName
        try {
            val intent = Intent()
            if (Utilities.isAtleastO()) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.notification_screen_error),
                Toast.LENGTH_SHORT
            )
            Log.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun showEnableNotificationSettingIfNeeded() {
        if (!isAtleastT()) {
            // notification permission is only needed for version 13 or above
            b.settingsActivityAppNotificationRl.visibility = View.GONE
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification permission is granted to the app, enable switch
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = true
        } else {
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isNotificationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
