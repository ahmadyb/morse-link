package com.morselink.feature.transfer

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
        viewModel.transportLabel.observe(viewLifecycleOwner) { label ->
            binding.transportLabel.text = label
        }
        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            binding.stats.text = stats
        }
        viewModel.pairing.observe(viewLifecycleOwner) { pairing ->
            binding.pairingPanel.isVisible = pairing.visible
            binding.qr.setImageBitmap(pairing.qr)
            binding.qr.isVisible = pairing.qr != null
            binding.pairingAddress.text = pairing.address ?: ""
            binding.pairingAddress.isVisible = !pairing.address.isNullOrBlank()
            binding.pairingHint.text = pairing.status.ifBlank {
                getString(R.string.transfer_pairing_waiting)
            }
        }

        binding.btnCancel.setOnClickListener { viewModel.cancelAll() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
