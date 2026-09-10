package com.morselink.feature.transfer

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.label
import com.morselink.feature.transfer.databinding.FragmentTransferBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * §14.5 — two independent sections, Sending and Receiving, because both
 * directions can be active in the same session.
 *
 * Doubles as the sender's pairing screen: while we are waiting for a receiver it
 * shows the QR code the other phone has to scan.
 */
@AndroidEntryPoint
class TransferFragment : Fragment(R.layout.fragment_transfer) {

    private val viewModel: TransferViewModel by viewModels()

    private var binding: FragmentTransferBinding? = null
    private val adapter = TransferAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentTransferBinding.bind(view)
        this.binding = binding

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewModel.rows.observe(viewLifecycleOwner) { rows -> adapter.submitList(rows) }
        viewModel.statusLine.observe(viewLifecycleOwner) { label ->
            binding.transportLabel.text = label
        }
        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            binding.stats.text = stats
        }
        binding.pairingHeader.setOnClickListener { viewModel.togglePairingCollapsed() }

        viewModel.pairing.observe(viewLifecycleOwner) { pairing ->
            binding.pairingPanel.isVisible = pairing.visible
            if (!pairing.visible) return@observe

            binding.pairingTitle.text = when (pairing.mode) {
                PairingMode.QR -> getString(R.string.transfer_pairing_title)
                PairingMode.CONNECTED ->
                    getString(R.string.transfer_connected_title, pairing.peerName.orEmpty())
                PairingMode.HIDDEN -> ""
            }

            binding.pairingBody.isVisible = !pairing.collapsed
            binding.qr.setImageBitmap(pairing.qr)
            binding.qr.isVisible = pairing.qr != null
            val host = pairing.address
            binding.pairingAddress.text = when {
                host.isNullOrBlank() -> ""
                pairing.port != null -> "$host:${pairing.port}"
                else -> host
            }
            binding.pairingAddress.isVisible = !host.isNullOrBlank()
            binding.pairingHint.text = pairing.status.ifBlank {
                getString(R.string.transfer_pairing_waiting)
            }

            // Chevron points up while the card is open and down once it is
            // collapsed, so the affordance reads as "tap to change this".
            binding.pairingToggle.rotation = if (pairing.collapsed) 90f else -90f
            binding.pairingToggle.contentDescription = getString(
                if (pairing.collapsed) R.string.transfer_panel_expand
                else R.string.transfer_panel_collapse
            )
        }

        // Only offered once there is a session to come back to. Before that
        // the QR is the whole point of the screen, and there is nothing to
        // minimise to.
        viewModel.canMinimise.observe(viewLifecycleOwner) { allowed ->
            binding.btnMinimise.isVisible = allowed
        }
        binding.btnMinimise.setOnClickListener { viewModel.minimise() }

        binding.btnCancel.setOnClickListener {
            binding.btnCancel.isEnabled = false
            viewModel.cancelAll()
        }

        // Minimise goes somewhere specific: Send, so the user can pick more
        // files. It has to be checked before the generic dismiss below, which
        // fires for both Minimise and Cancel.
        viewModel.minimised.observe(viewLifecycleOwner) { minimised ->
            if (!minimised) return@observe
            findNavController().navigate(Uri.parse("morselink://send"))
        }

        // Cancel used to stop the transfers but leave the user stranded on a
        // dead screen with no way back but a force-close.
        viewModel.dismiss.observe(viewLifecycleOwner) { done ->
            if (!done) return@observe
            // Minimise posts dismiss too, and it has already navigated.
            if (viewModel.minimised.value == true) return@observe
            val nav = findNavController()
            // navigateUp() returns false when there is nowhere to go up to,
            // which happens when this screen was the first one shown after a
            // crash-restart. Without the fallback Cancel appears to do nothing
            // and the only way out is to kill the app.
            if (!nav.navigateUp()) {
                runCatching { nav.popBackStack() }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
