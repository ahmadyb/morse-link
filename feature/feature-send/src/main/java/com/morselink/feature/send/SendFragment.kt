package com.morselink.feature.send

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import android.net.Uri
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.morselink.core.media.SortOrder
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.ui.Format
import com.morselink.feature.send.databinding.FragmentSendBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * §14.2 — five category tabs, search, sort, date-grouped multi-select.
 *
 * The tab index is the only source of truth. Changing it clears the list before
 * the new data is requested and the layout mode is switched on the existing
 * adapter, so the active tab label can never sit above another tab's content.
 */
@AndroidEntryPoint
class SendFragment : Fragment(R.layout.fragment_send) {

    @Inject
    lateinit var connection: ConnectionHolder

    private val viewModel: SendViewModel by viewModels()

    private var binding: FragmentSendBinding? = null
    private lateinit var adapter: SendAdapter

    /** Spinner fires a selection callback as soon as it is laid out. */
    private var suppressSortCallback = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSendBinding.bind(view)
        this.binding = binding

        SendTab.values().forEach { tab ->
            binding.tabs.addTab(binding.tabs.newTab().setText(tab.title))
        }

        adapter = SendAdapter(
            onToggle = { row ->
                if (row is SendRow.Category) viewModel.openCategory(row.category)
                else {
                    viewModel.toggle(row)
                    refreshSelectionBar()
                }
            },
            isSelected = { viewModel.isSelected(it) },
            onOpenCategory = { viewModel.openCategory(it.category) },
        )
        binding.list.adapter = adapter

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val value = SendTab.values()[tab.position]
                viewModel.setTab(value)
                applyLayoutMode(value.useGrid)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        val initial = viewModel.tab.value ?: SendTab.PHOTOS
        applyLayoutMode(initial.useGrid)
        binding.tabs.getTabAt(initial.ordinal)?.select()

        binding.sortSpinner.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.send_sort_options,
            android.R.layout.simple_spinner_dropdown_item,
        )
        binding.sortSpinner.setSelection(viewModel.currentSort().toIndex())
        binding.sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSortCallback) {
                    suppressSortCallback = false
                    return
                }
                viewModel.setSort(SortOrder.values().getOrElse(position) { SortOrder.DATE })
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
            adapter.submitList(rows)
            updateEmptyState(rows.isEmpty())
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progress.isVisible = loading
            if (loading) binding.empty.isVisible = false
            else updateEmptyState(viewModel.rows.value.isNullOrEmpty())
        }
        viewModel.sort.observe(viewLifecycleOwner) { order ->
            val index = order.toIndex()
            if (binding.sortSpinner.selectedItemPosition != index) {
                suppressSortCallback = true
                binding.sortSpinner.setSelection(index)
            }
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
            connection.pendingOutgoing = picked
            findNavController().navigate(Uri.parse("morselink://transfer"))
        }

        viewModel.consumeExternalFiles()
        refreshSelectionBar()
    }

    /** Empty only once a load has finished and genuinely returned nothing. */
    private fun updateEmptyState(empty: Boolean) {
        val binding = binding ?: return
        val loading = viewModel.loading.value == true
        binding.empty.isVisible = empty && !loading
    }

    private fun refreshSelectionBar() {
        val binding = binding ?: return
        val count = viewModel.selection.size()
        binding.selectionBar.isVisible = count > 0
        binding.btnSend.text = if (count > 0) {
            getString(R.string.send_action_with_count, count, Format.bytes(viewModel.selectedBytes()))
        } else getString(com.morselink.core.ui.R.string.action_send)
        // Selection lives outside the row model, so rebind the visible rows.
        adapter.notifyDataSetChanged()
    }

    /** Switches layout on the existing adapter; recreating it dropped the list. */
    private fun applyLayoutMode(useGrid: Boolean) {
        val binding = binding ?: return
        adapter.useGrid = useGrid
        binding.list.layoutManager = if (useGrid) {
            GridLayoutManager(requireContext(), 3, RecyclerView.VERTICAL, false).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (position < adapter.itemCount && adapter.isHeader(position)) spanCount else 1
                }
            }
        } else {
            LinearLayoutManager(requireContext())
        }
        binding.sortLabel.isVisible = true
        binding.sortSpinner.isVisible = true
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
