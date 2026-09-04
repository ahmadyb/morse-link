package com.morselink.feature.send

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.morselink.core.ui.Format
import com.morselink.feature.send.databinding.FragmentSendBinding
import dagger.hilt.android.AndroidEntryPoint

/** §14.2 — five category tabs (Contacts is gone), search, sort, multi-select. */
@AndroidEntryPoint
class SendFragment : Fragment(R.layout.fragment_send) {

    private val viewModel: SendViewModel by viewModels()

    private var binding: FragmentSendBinding? = null
    private var adapter: SendAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSendBinding.bind(view)
        this.binding = binding

        SendTab.values().forEach { tab ->
            binding.tabs.addTab(binding.tabs.newTab().setText(tab.title))
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.setTab(SendTab.values()[tab.position])
                applyLayoutMode(SendTab.values()[tab.position].useGrid)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        adapter = SendAdapter(
            useGrid = true,
            onToggle = { row ->
                if (row is SendRow.Category) {
                    viewModel.openCategory(row.category)
                } else {
                    viewModel.toggle(row)
                    refreshSelectionBar()
                }
            },
            isSelected = { viewModel.isSelected(it) },
        )
        binding.list.adapter = adapter
        applyLayoutMode(true)

        binding.sortSpinner.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.send_sort_options,
            android.R.layout.simple_spinner_dropdown_item,
        )
        binding.sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.setSort(position.fromSortIndex())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.setQuery(s?.toString().orEmpty())
                }
            }
        )

        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter?.submitList(rows)
            binding.empty.isVisible = rows.isEmpty()
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progress.isVisible = loading
        }

        binding.btnClear.setOnClickListener {
            viewModel.clearSelection()
            refreshSelectionBar()
        }
        binding.btnSend.setOnClickListener {
            val picked = viewModel.selection.snapshot()
            if (picked.isEmpty()) {
                binding.btnSend.setText(R.string.send_no_selection)
                return@setOnClickListener
            }
            findNavController().navigate(R.id.action_send_to_transfer)
        }

        viewModel.consumeExternalFiles()
        refreshSelectionBar()
    }

    private fun refreshSelectionBar() {
        val binding = binding ?: return
        val count = viewModel.selection.size()
        binding.selectionBar.isVisible = count > 0
        binding.btnSend.text = if (count > 0) {
            getString(R.string.send_action_with_count, count, Format.bytes(viewModel.selectedBytes()))
        } else getString(com.morselink.core.ui.R.string.action_send)
        adapter?.notifyDataSetChanged()
    }

    private fun applyLayoutMode(useGrid: Boolean) {
        val binding = binding ?: return
        binding.list.layoutManager = if (useGrid) {
            GridLayoutManager(requireContext(), 3, RecyclerView.VERTICAL, false)
        } else {
            LinearLayoutManager(requireContext())
        }
        adapter = SendAdapter(
            useGrid = useGrid,
            onToggle = { row ->
                if (row is SendRow.Category) viewModel.openCategory(row.category)
                else {
                    viewModel.toggle(row)
                    refreshSelectionBar()
                }
            },
            isSelected = { viewModel.isSelected(it) },
        )
        binding.list.adapter = adapter
        viewModel.rows.value?.let { adapter?.submitList(it) }
        binding.sortLabel.isVisible = !useGrid || viewModel.tab == SendTab.PHOTOS || viewModel.tab == SendTab.VIDEOS
        binding.sortSpinner.isVisible = binding.sortLabel.isVisible
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

private fun Int.fromSortIndex(): com.morselink.core.media.SortOrder = when (this) {
    1 -> com.morselink.core.media.SortOrder.SIZE
    2 -> com.morselink.core.media.SortOrder.NAME
    else -> com.morselink.core.media.SortOrder.DATE
}

private fun Spinner.configure() = Unit
