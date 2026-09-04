package com.morselink.feature.transfer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.core.ui.Format
import com.morselink.core.transfer.model.TransferDirection
import com.morselink.core.transfer.model.label
import com.morselink.feature.transfer.databinding.FragmentTransferBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * §14.5 — two independent sections, Sending and Receiving, because both
 * directions can be active in the same session.
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
        binding.btnCancel.setOnClickListener { viewModel.cancelAll() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
