package com.morselink.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.morselink.app.databinding.ActivityMainBinding
import com.morselink.core.transfer.model.TransferableFile
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

        requestBaselinePermissions()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Files shared from another app land straight in the Send flow. */
    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND || action != Intent.ACTION_SEND_MULTIPLE) {
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        }
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
