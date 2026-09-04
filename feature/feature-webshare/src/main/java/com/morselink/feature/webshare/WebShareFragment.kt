package com.morselink.feature.webshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.morselink.feature.webshare.databinding.FragmentWebshareBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * §4.5 / §14.5 — phone ↔ PC over a local HTTP server. The raw address is always
 * visible next to the QR code; mDNS is only ever a convenience on top.
 */
@AndroidEntryPoint
class WebShareFragment : Fragment(R.layout.fragment_webshare) {

    private val viewModel: WebShareViewModel by viewModels()

    private var binding: FragmentWebshareBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentWebshareBinding.bind(view)
        this.binding = binding

        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.connectedCard.isVisible = state.running
            binding.status.text = if (state.running) {
                getString(R.string.webshare_running)
            } else getString(R.string.webshare_idle)
            binding.btnServer.text = getString(
                if (state.running) R.string.webshare_stop else R.string.webshare_start
            )
            binding.btnHotspot.text = getString(
                if (state.hotspotActive) R.string.webshare_hotspot_stop else R.string.webshare_hotspot
            )
            binding.url.text = state.url
            binding.hotspotInfo.text = state.hotspotInfo
            if (state.running && state.url.isNotBlank()) {
                binding.qr.setImageBitmap(state.url.toQrCode())
            }
        }

        viewModel.message.observe(viewLifecycleOwner) { message ->
            message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }

        binding.btnServer.setOnClickListener { viewModel.toggleServer() }
        binding.btnHotspot.setOnClickListener { viewModel.toggleHotspot() }
        binding.btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("webshare", binding.url.text))
            Toast.makeText(requireContext(), com.morselink.core.ui.R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

private fun String.toQrCode(size: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = MultiFormatWriter().encode(this, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()
