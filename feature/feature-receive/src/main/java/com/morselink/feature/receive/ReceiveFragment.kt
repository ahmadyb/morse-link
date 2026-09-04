package com.morselink.feature.receive

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.morselink.core.ui.Dialogs
import com.morselink.feature.receive.databinding.FragmentReceiveBinding
import dagger.hilt.android.AndroidEntryPoint

/** §14.8 — full-screen scanner with a manual entry fallback that is always visible. */
@AndroidEntryPoint
class ReceiveFragment : Fragment(R.layout.fragment_receive) {

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
            if (connected) findNavController().navigate(R.id.action_receive_to_transfer)
        }

        startCameraWithPermissionCheck()
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
                Toast.makeText(context, R.string.receive_camera_unavailable, Toast.LENGTH_SHORT).show()
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
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure {
                Toast.makeText(context, R.string.receive_camera_unavailable, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun showManualEntry() {
        val context = requireContext()
        val ip = android.widget.EditText(context).apply { hint = "192.168.43.1" }
        val port = android.widget.EditText(context).apply {
            hint = "54321"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(ip)
            addView(port)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.receive_manual_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Connect") { _, _ ->
                val enteredPort = port.text.toString().toIntOrNull() ?: 54321
                viewModel.connectManually(ip.text.toString().trim(), enteredPort)
            }
            .show()
    }

    override fun onDestroyView() {
        scanner = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val REQUEST_CAMERA = 2101
    }
}
