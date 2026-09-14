package com.morselink.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.morselink.core.data.db.TransferEntity
import com.morselink.core.ui.Format
import com.morselink.feature.settings.databinding.FragmentHistoryBinding
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/** §14.6 — Received / Sent toggle over a date-grouped list of real transfers. */
@AndroidEntryPoint
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val viewModel: HistoryViewModel by viewModels()

    private var binding: FragmentHistoryBinding? = null
    private lateinit var adapter: HistoryAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHistoryBinding.bind(view)
        this.binding = binding

        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.history_received)))
        binding.tabs.addTab(binding.tabs.newTab().setText(getString(R.string.history_sent)))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = viewModel.setDirection(
                if (tab.position == 0) TransferEntity.DIRECTION_RECEIVED else TransferEntity.DIRECTION_SENT
            )
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        adapter = HistoryAdapter { item -> open(item) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submitList(rows)
            binding.empty.isVisible = rows.isEmpty()
        }
    }

    private fun open(item: TransferEntity) {
        val file = item.localPath?.let { File(it) }
        val uri: Uri? = when {
            item.uriString != null -> Uri.parse(item.uriString)
            file != null && file.exists() -> FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
            else -> null
        }
        if (uri == null) {
            Toast.makeText(requireContext(), "File is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = item.mimeType
        val intent = if (mime == "application/vnd.android.package-archive") {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(requireContext(), "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

sealed interface HistoryRow {
    data class Header(val label: String) : HistoryRow
    data class Entry(val item: TransferEntity) : HistoryRow {
        val meta: String
            get() = "${Format.bytes(item.sizeBytes)} · ${Format.time(item.timestamp)} · ${item.peerName}"
    }
}
