package com.morselink.feature.receive

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import android.net.Uri
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.morselink.core.network.NetworkUtils
import com.morselink.core.network.PairingPayload
import com.morselink.core.ui.Dialogs
import com.morselink.feature.receive.databinding.FragmentReceiveBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** §14.8 — full-screen scanner with a manual fallback that is always visible. */
@AndroidEntryPoint
class ReceiveFragment : Fragment(R.layout.fragment_receive) {

    @Inject
    lateinit var network: NetworkUtils

    private val viewModel: ReceiveViewModel by viewModels()

    private var binding: FragmentReceiveBinding? = null
    private var scanner: QrScanner? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentReceiveBinding.bind(view)
        this.binding = binding

        binding.btnClose.setOnClickListener { findNavController().popBackStack() }
        binding.btnManual.setOnClickListener { showManualEntry() }

        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.hint.text = status
        }
        viewModel.connected.observe(viewLifecycleOwner) { connected ->
            if (connected) findNavController().navigate(Uri.parse("morselink://transfer"))
        }

        // Nearby Connections starts BLE scanning as soon as discovery begins,
        // which flips the Bluetooth icon on with no explanation. Say why once.
        showBluetoothNoteOnce()

        startCameraWithPermissionCheck()
    }

    private fun showBluetoothNoteOnce() {
        val prefs = runCatching {
            requireContext().getSharedPreferences("morselink_hints", 0)
        }.getOrNull() ?: return
        if (prefs.getBoolean(KEY_BLUETOOTH_NOTE_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_BLUETOOTH_NOTE_SHOWN, true).apply()
        Toast.makeText(
            requireContext(),
            R.string.receive_bluetooth_note,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun startCameraWithPermissionCheck() {
        val activity = requireActivity()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            showCameraFailure()
            Dialogs.permissionRationale(
                requireContext(),
                getString(R.string.receive_camera_needed),
                "Morselink only uses the camera to read a pairing QR code. Nothing is stored or uploaded.",
            )
        }
    }

    private fun bindCamera() {
        val context = requireContext()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                showCameraFailure()
                return@addListener
            }
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(binding?.preview?.surfaceProvider)
            }
            val analysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            scanner = QrScanner { text -> viewModel.onQrScanned(text) }
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context), scanner!!)
            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.isSuccess
            if (!bound) showCameraFailure()
        }, ContextCompat.getMainExecutor(context))
    }

    /** A black viewfinder with no message looks like a hang; offer a retry. */
    private fun showCameraFailure() {
        val binding = binding ?: return
        binding.hint.text = getString(R.string.receive_camera_failed)
        binding.hint.isVisible = true
        binding.hint.setOnClickListener {
            binding.hint.setOnClickListener(null)
            binding.hint.text = getString(R.string.receive_hint)
            startCameraWithPermissionCheck()
        }
        Toast.makeText(requireContext(), R.string.receive_camera_unavailable, Toast.LENGTH_SHORT).show()
    }

    /**
     * The gateway address is deterministic in the primary flow (the phone hosting
     * the hotspot is always .1), so only the port is actually worth typing. The
     * address is shown read-only above the field rather than silently prefilled.
     */
    private fun showManualEntry() {
        val context = requireContext()
        val gateway = runCatching { network.hotspotGatewayIp() }.getOrNull()
            ?: NetworkUtils.DEFAULT_GATEWAY

        val addressNote = TextView(context).apply {
            text = getString(R.string.receive_manual_address, gateway)
            setTextColor(ContextCompat.getColor(context, com.morselink.core.ui.R.color.textSecondary))
            textSize = 13f
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        val port = android.widget.EditText(context).apply {
            hint = getString(R.string.receive_manual_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(addressNote)
            addView(port)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.receive_manual_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.receive_manual_connect) { _, _ ->
                val enteredPort = port.text.toString().toIntOrNull() ?: PairingPayload.DEFAULT_PORT
                viewModel.connectManually(gateway, enteredPort)
            }
            .show()
    }

    override fun onDestroyView() {
        scanner = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val REQUEST_CAMERA = 2101
        private const val KEY_BLUETOOTH_NOTE_SHOWN = "bluetooth_note_shown"
    }
}
