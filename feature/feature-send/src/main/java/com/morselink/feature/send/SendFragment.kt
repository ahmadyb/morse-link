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

    /** Whether the minimised session bar is showing its detail line. */
    private var sessionBarCollapsed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSendBinding.bind(view)
        this.binding = binding

        SendTab.values().forEach { tab ->
            binding.tabs.addTab(binding.tabs.newTab().setText(tab.title))
        }
        // One tab is not a choice, and a lone tab strip reads as though
        // something is missing beside it. The libraries it used to hold are
        // rows in this list now.
        binding.tabs.isVisible = SendTab.values().size > 1

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
            onOpenMediaGroup = { viewModel.openMediaGroup(it) },
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

        val initial = viewModel.tab.value ?: SendTab.FILES
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
            refreshLevel()
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
            // Anything already queued goes too. A batch left behind here is
            // invisible on this screen but still in line to be sent, so the
            // next send would pick up the files the user had just discarded.
            connection.pendingOutgoing = emptyList()
            refreshSelectionBar()
        }
        binding.btnSend.setOnClickListener {
            val picked = viewModel.selection.snapshot()
            if (picked.isEmpty()) {
                binding.btnSend.setText(R.string.send_no_selection)
                return@setOnClickListener
            }
            connection.pendingOutgoing = picked
            // The queue owns them now. Leaving them ticked meant coming back
            // to a screen that still showed every file selected, where the
            // next tap removed one from a batch that was already on its way.
            viewModel.clearSelection()
            refreshSelectionBar()
            findNavController().navigate(Uri.parse("morselink://transfer"))
        }

        binding.sessionToggle.setOnClickListener {
            sessionBarCollapsed = !sessionBarCollapsed
            refreshSessionBar()
        }
        binding.sessionBody.setOnClickListener {
            findNavController().navigate(Uri.parse("morselink://transfer"))
        }

        viewModel.consumeExternalFiles()
        refreshSelectionBar()
        refreshSessionBar()
    }

    override fun onStart() {
        super.onStart()
        // Deeper than the top level, Back should step out of the folder rather
        // than leave the screen, or there is no way back to the categories
        // short of restarting the app.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (!viewModel.navigateUp()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }.also { backCallback = it },
        )
    }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    override fun onResume() {
        super.onResume()
        // The session can come up or end while we are in the background.
        refreshSessionBar()
    }

    /**
     * Where the list currently is. The tab strip is gone, so this is the only
     * thing that says the rows on screen are Photos and not everything.
     */
    private fun refreshLevel() {
        val binding = binding ?: return
        val label = viewModel.openedLabel()
        backCallback?.isEnabled = label != null
        binding.search.hint = if (label == null) "Search files" else "Search $label"
    }

    /** Empty only once a load has finished and genuinely returned nothing. */
    private fun updateEmptyState(empty: Boolean) {
        val binding = binding ?: return
        val loading = viewModel.loading.value == true
        binding.empty.isVisible = empty && !loading
    }

    /**
     * The minimised session. Shown only while a session is actually up, and
     * tappable so the user can get back to the transfer screen. The subtitle
     * collapses away to leave a one-line strip.
     */
    private fun refreshSessionBar() {
        val binding = binding ?: return
        val peer = connection.peer
        if (!connection.hasSession() || peer == null) {
            binding.sessionBar.isVisible = false
            return
        }
        binding.sessionBar.isVisible = true
        binding.sessionTitle.text = getString(R.string.session_bar_title, peer.name)
        binding.sessionSubtitle.isVisible = !sessionBarCollapsed
        binding.sessionToggle.rotation = if (sessionBarCollapsed) 90f else -90f
        binding.sessionToggle.contentDescription = getString(
            if (sessionBarCollapsed) com.morselink.core.ui.R.string.action_expand
            else com.morselink.core.ui.R.string.action_collapse
        )
    }

    private fun refreshSelectionBar() {
        val binding = binding ?: return
        val count = viewModel.selection.size()
        binding.selectionBar.isVisible = count > 0
        // With a session already up this adds to it rather than starting a new
        // one, so say where the files are going.
        binding.btnSend.text = when {
            count == 0 -> getString(com.morselink.core.ui.R.string.action_send)
            connection.hasSession() && connection.peer != null ->
                getString(R.string.send_action_to_peer, count,
                    Format.bytes(viewModel.selectedBytes()), connection.peer!!.name)
            else -> getString(R.string.send_action_with_count, count,
                Format.bytes(viewModel.selectedBytes()))
        }
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
