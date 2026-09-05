package com.morselink.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import android.net.Uri
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.morselink.core.data.prefs.ThemeMode
import com.morselink.core.ui.CrashLog
import com.morselink.core.ui.Dialogs
import com.morselink.feature.settings.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

/** §14.7 — grouped preferences with the protocol knobs demoted into Advanced. */
@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()

    private var binding: FragmentSettingsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view)
        this.binding = binding

        viewModel.state.observe(viewLifecycleOwner) { settings ->
            binding.rowDeviceName.title.text = getString(R.string.settings_device_name)
            binding.rowDeviceName.value.text = settings.deviceName

            binding.rowAvatar.title.text = getString(R.string.settings_avatar)
            binding.rowAvatar.subtitle.text = "Pick a colour for your device badge"

            binding.rowDownloadDir.title.text = getString(R.string.settings_download_dir)
            binding.rowDownloadDir.value.text = settings.downloadDirectory
                ?: viewModel.defaultDownloadPath()

            binding.rowPreferWifiDirect.title.text = getString(R.string.settings_prefer_wifi_direct)
            binding.rowPreferWifiDirect.toggle.isChecked = settings.preferWifiDirectOverHotspot

            binding.rowTheme.title.text = getString(R.string.settings_theme)
            binding.rowTheme.value.text = settings.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }

            binding.rowNotifications.title.text = getString(R.string.settings_notifications)
            binding.rowNotifications.toggle.isChecked = settings.transferNotifications

            binding.rowSounds.title.text = getString(R.string.settings_sounds)
            binding.rowSounds.toggle.isChecked = settings.soundsEnabled

            binding.rowTimeout.title.text = getString(R.string.settings_timeout)
            binding.rowTimeout.value.text = settings.connectionTimeoutSeconds.toString()

            binding.rowReconnect.title.text = getString(R.string.settings_reconnect)
            binding.rowReconnect.value.text = settings.maxReconnectAttempts.toString()

            binding.rowWebshare.title.text = getString(R.string.settings_webshare)
            binding.rowWebshare.subtitle.text = "Transfer between this phone and a PC browser"

            binding.rowVersion.title.text = getString(R.string.settings_version)
            binding.rowVersion.value.text = viewModel.versionName()

            binding.rowPrivacy.title.text = getString(R.string.settings_privacy)
            binding.rowPrivacy.subtitle.text = "Offline by design"
        }

        setupCrashLogRow()

        binding.rowDeviceName.root.setOnClickListener {
            Dialogs.input(requireContext(), getString(R.string.settings_device_name), viewModel.deviceName()) {
                if (it.isNotBlank()) viewModel.setDeviceName(it)
            }
        }
        binding.rowAvatar.root.setOnClickListener { viewModel.cycleAvatar() }
        binding.rowDownloadDir.root.setOnClickListener {
            Dialogs.input(
                requireContext(),
                getString(R.string.settings_download_dir),
                viewModel.downloadDirectory(),
            ) { if (it.isNotBlank()) viewModel.setDownloadDirectory(it) }
        }
        binding.rowPreferWifiDirect.toggle.setOnCheckedChangeListener { _, checked ->
            viewModel.setPreferWifiDirect(checked)
        }
        binding.rowTheme.root.setOnClickListener { showThemePicker() }
        binding.rowNotifications.toggle.setOnCheckedChangeListener { _, checked ->
            viewModel.setNotifications(checked)
        }
        binding.rowSounds.toggle.setOnCheckedChangeListener { _, checked ->
            viewModel.setSounds(checked)
        }
        binding.rowTimeout.root.setOnClickListener {
            Dialogs.input(requireContext(), getString(R.string.settings_timeout), viewModel.timeout().toString()) {
                it.toIntOrNull()?.let(viewModel::setTimeout)
            }
        }
        binding.rowReconnect.root.setOnClickListener {
            Dialogs.input(requireContext(), getString(R.string.settings_reconnect), viewModel.reconnect().toString()) {
                it.toIntOrNull()?.let(viewModel::setReconnect)
            }
        }
        binding.rowWebshare.root.setOnClickListener {
            findNavController().navigate(Uri.parse("morselink://webshare"))
        }
        binding.rowPrivacy.root.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_privacy_title)
                .setMessage(R.string.history_privacy_body)
                .setPositiveButton(com.morselink.core.ui.R.string.action_ok, null)
                .show()
        }
    }

    /**
     * The crash log is how a crash gets reported without a USB cable: the
     * handler writes the trace to a private file during process death, and this
     * row surfaces it so it can be read or copied out.
     */
    private fun setupCrashLogRow() {
        val binding = binding ?: return
        binding.rowCrashLog.title.text = getString(R.string.settings_crash_log)
        refreshCrashLogRow()
        binding.rowCrashLog.root.setOnClickListener { showCrashLog() }
    }

    private fun refreshCrashLogRow() {
        val binding = binding ?: return
        val log = CrashLog.read(requireContext())
        binding.rowCrashLog.subtitle.text = if (log == null) {
            getString(R.string.settings_crash_log_none)
        } else {
            getString(R.string.settings_crash_log_count, CrashLog.entryCount(log))
        }
    }

    private fun showCrashLog() {
        val context = requireContext()
        val log = CrashLog.read(context)
        if (log == null) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_crash_log)
                .setMessage(R.string.settings_crash_log_empty)
                .setPositiveButton(com.morselink.core.ui.R.string.action_ok, null)
                .show()
            return
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply {
            text = log
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val scroller = ScrollView(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(body)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_crash_log)
            .setView(scroller)
            .setPositiveButton(com.morselink.core.ui.R.string.action_close, null)
            .setNegativeButton(com.morselink.core.ui.R.string.action_clear) { _, _ ->
                CrashLog.clear(context)
                refreshCrashLogRow()
            }
            .setNeutralButton(com.morselink.core.ui.R.string.action_copy) { _, _ -> copyCrashLog(log) }
            .show()
    }

    private fun copyCrashLog(text: String) {
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("morselink-crash", text))
        Toast.makeText(
            requireContext(),
            com.morselink.core.ui.R.string.copied,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showThemePicker() {
        val options = arrayOf("Light", "Dark", "System")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setItems(options) { _, which ->
                viewModel.setTheme(
                    when (which) {
                        0 -> ThemeMode.LIGHT
                        1 -> ThemeMode.DARK
                        else -> ThemeMode.SYSTEM
                    }
                )
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        0 -> AppCompatDelegate.MODE_NIGHT_NO
                        1 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
            .show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
