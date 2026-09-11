package com.morselink.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.morselink.app.databinding.ActivityMainBinding
import com.morselink.core.transfer.model.TransferableFile
import com.morselink.core.ui.CrashLog
import com.morselink.core.ui.Permissions
import com.morselink.feature.send.SendArgs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var permissions: Permissions

    @Inject
    lateinit var sendArgs: SendArgs

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                permissions.explain(
                    this,
                    "Permissions needed",
                    "Morselink needs these to find nearby devices and read the files you pick. " +
                        "Local discovery never tracks your location.",
                )
            }
            pendingPermissions?.invoke(denied.isEmpty())
            pendingPermissions = null
        }

    private var pendingPermissions: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController
        binding.bottomNav.setupWithNavController(navController)

        // Send/Receive/Transfer/WebShare are focused sub-flows, not tabs: leaving
        // the global bottom bar on screen made the Send flow look like the Files
        // tab and gave two ways to leave a half-finished selection.
        val topLevelDestinations = setOf(
            R.id.nav_connect,
            R.id.nav_files,
            R.id.nav_history,
            R.id.nav_settings,
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.isVisible = destination.id in topLevelDestinations
        }

        showPendingCrash()
        requestBaselinePermissions()
        handleIncomingIntent(intent)
    }

    /**
     * A crash has to be surfaced from somewhere the user can actually reach.
     * Putting the log only in Settings was a mistake: a crash *in* Settings
     * made it permanently unreachable. Offering it on launch also means it can
     * be photographed, which is the channel this user reports through.
     */
    private fun showPendingCrash() {
        if (!CrashLog.hasUnseen(this)) return
        CrashLog.markSeen(this)
        val log = CrashLog.read(this) ?: return
        val text = CrashLog.newest(log).trim()

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val body = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val scroller = ScrollView(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(body)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Morselink crashed")
            .setMessage(
                "The crash below was recorded on the last run. Screenshot it or " +
                    "tap Copy, then send it over so it can be fixed."
            )
            .setView(scroller)
            .setPositiveButton("Copy") { _, _ -> copyToClipboard("morselink-crash", text) }
            .setNegativeButton("Clear") { _, _ -> CrashLog.clear(this) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, com.morselink.core.ui.R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Files shared from another app land straight in the Send flow. */
    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris: List<android.net.Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list ?: emptyList()
            }
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        sendArgs.externalUris = uris.map { uri ->
            TransferableFile(
                id = uri.toString(),
                name = uri.lastPathSegment ?: "shared_file",
                sizeBytes = 0L,
                mimeType = contentResolver.getType(uri),
                uri = uri,
            )
        }
        navController.navigate(R.id.nav_send)
    }

    private fun requestBaselinePermissions() {
        val needed = buildList {
            addAll(permissions.forMedia())
            addAll(permissions.forNotifications())
        }
        val missing = permissions.missing(needed)
        if (missing.isEmpty()) return
        permissionLauncher.launch(missing.toTypedArray())
    }

    fun withPermissions(
        wanted: List<String>,
        rationale: String = "Morselink needs these permissions to continue.",
        onResult: (Boolean) -> Unit,
    ) {
        val missing = permissions.missing(wanted)
        if (missing.isEmpty()) {
            onResult(true)
            return
        }
        pendingPermissions = onResult
        permissionLauncher.launch(missing.toTypedArray())
        permissions.explain(this, "Permission required", rationale)
    }

    companion object {
        const val EXTRA_TARGET_WEBSHARE = "target_webshare"
    }
}
