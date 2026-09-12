package com.morselink.feature.filemanager

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.core.media.DirectoryState
import com.morselink.core.media.FileItem
import com.morselink.core.media.SmartCategory
import com.morselink.core.media.SortOrder
import com.morselink.core.ui.AddressSegment
import com.morselink.core.ui.Dialogs
import com.morselink.core.ui.Format
import com.morselink.feature.filemanager.databinding.FragmentFileManagerBinding
import com.morselink.core.ui.Permissions
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import android.net.Uri
import androidx.navigation.fragment.findNavController
import com.morselink.core.network.ConnectionHolder
import com.morselink.core.transfer.model.TransferableFile

/**
 * §14.3 / §14.4 — smart categories on entry, raw folder navigation after that,
 * with the long-press action toolbar for share/delete/rename/move/copy/compress.
 */
@AndroidEntryPoint
class FileManagerFragment : Fragment(R.layout.fragment_file_manager) {

    /** The live session, so files picked here can be handed straight to it. */
    @Inject
    lateinit var connection: ConnectionHolder

    @Inject
    lateinit var permissions: Permissions

    private val viewModel: FileManagerViewModel by viewModels()

    private var binding: FragmentFileManagerBinding? = null
    private lateinit var adapter: FileAdapter

    override fun onStart() {
        super.onStart()
        // Inside a library or a category, Back steps out rather than leaving
        // the app - with a single tab there is no other way back to the top.
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

    /** The sort spinner fires a selection callback the moment it is laid out. */
    private var suppressSortCallback = true

    private fun tabLabel(tab: FilesTab): String = when (tab) {
        FilesTab.FILES -> getString(R.string.files_tab_files)
        FilesTab.PHOTOS -> MediaLibrary.PHOTOS.label()
        FilesTab.VIDEOS -> MediaLibrary.VIDEOS.label()
        FilesTab.MUSIC -> MediaLibrary.MUSIC.label()
        FilesTab.APPS -> MediaLibrary.APPS.label()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentFileManagerBinding.bind(view)
        this.binding = binding

        adapter = FileAdapter(
            onClick = { item -> viewModel.onItemClick(item) },
            onLongClick = { item ->
                viewModel.toggleSelection(item)
                renderSelection()
                true
            },
            isSelected = { viewModel.isSelected(it) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        // Rows throughout. The tabs across the top separate the libraries, and
        // a grid of thumbnails underneath them was a second, slower way to say
        // the same thing.
        adapter.useGrid = false
        adapter.libraryTiles = false
        applyLayout(false)

        // ------------------------------------------------------------ tabs
        FilesTab.values().forEach { tab ->
            binding.tabs.addTab(binding.tabs.newTab().setText(tabLabel(tab)))
        }
        binding.tabs.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                viewModel.setTab(FilesTab.values()[tab.position])
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        // The strip has to follow the list, not just lead it: opening Photos
        // from a tile, or pressing Back out of a folder, moves the list without
        // ever touching the strip.
        viewModel.tab.observe(viewLifecycleOwner) { tab ->
            val index = FilesTab.values().indexOf(tab)
            if (index >= 0 && binding.tabs.selectedTabPosition != index) {
                binding.tabs.getTabAt(index)?.select()
            }
            // There is no directory to be inside of while a library is open.
            binding.addressBar.isVisible = tab == FilesTab.FILES
        }

        // ---------------------------------------------------------- search
        binding.search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.setQuery(s?.toString().orEmpty())
                }
            }
        )

        // ------------------------------------------------------------ sort
        binding.sortSpinner.adapter = android.widget.ArrayAdapter.createFromResource(
            requireContext(),
            R.array.files_sort_options,
            android.R.layout.simple_spinner_dropdown_item,
        )
        binding.sortSpinner.setSelection(viewModel.currentSort().ordinal)
        suppressSortCallback = true
        binding.sortSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    // The spinner fires once as soon as it is laid out.
                    if (suppressSortCallback) {
                        suppressSortCallback = false
                        return
                    }
                    viewModel.setSort(SortOrder.values().getOrElse(position) { SortOrder.DATE })
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }

        binding.btnClear.setOnClickListener { clearSelection() }
        binding.btnSend.setOnClickListener { sendSelection() }

        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submitList(rows)
            updateEmptyState()
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progress.isVisible = loading
            updateEmptyState()
        }
        viewModel.state.observe(viewLifecycleOwner) { updateEmptyState() }
        viewModel.breadcrumbs.observe(viewLifecycleOwner) { crumbs ->
            binding.addressBar.setSegments(crumbs.map { AddressSegment(it.label, it.path) })
        }
        binding.addressBar.onSegmentClick = { _, segment -> viewModel.navigateTo(segment.key) }
        binding.addressBar.onPathSubmitted = { path -> viewModel.navigateTo(path) }
        binding.addressBar.onCaretClick = { index, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                val siblings = viewModel.siblingsAt(index)
                binding.addressBar.showSiblings(
                    index,
                    siblings.map { AddressSegment(it.label, it.path) },
                )
            }
        }
        viewModel.storage.observe(viewLifecycleOwner) { info ->
            binding.storageBar.progress = (info.usedFraction * 100).toInt()
            binding.storageText.text = "${Format.bytes(info.usedBytes)} / ${Format.bytes(info.totalBytes)}"
        }

        // Sending belongs here as much as in the Send tab: this is where the
        // files are. Pick them, press Send, and the transfer screen connects
        // and sends - or connect first and come back to choose.
        binding.actionSend.setOnClickListener { sendSelection() }
        binding.actionShare.setOnClickListener { shareSelection() }
        binding.actionDelete.setOnClickListener { deleteSelection() }
        binding.actionRename.setOnClickListener { renameSelection() }
        binding.actionMove.setOnClickListener { pickDirectoryAnd(move = true) }
        binding.actionCopy.setOnClickListener { pickDirectoryAnd(move = false) }
        binding.actionCompress.setOnClickListener { compressSelection() }
        binding.actionProperties.setOnClickListener { showProperties() }

        ensureStorageAccess()
    }

    private fun ensureStorageAccess() {
        val needed = permissions.forMedia()
        if (permissions.missing(needed).isEmpty() &&
            (android.os.Build.VERSION.SDK_INT < 30 || permissions.hasAllFilesAccess())
        ) {
            viewModel.refresh()
            return
        }
        Dialogs.permissionRationale(
            requireContext(),
            getString(R.string.files_storage_needed),
            "Morselink reads files locally to send or manage them. Nothing is uploaded, and file access never leaves this device.",
        )
        if (android.os.Build.VERSION.SDK_INT >= 30 && !permissions.hasAllFilesAccess()) {
            permissions.requestAllFilesAccess(requireActivity())
        } else {
            requestPermissions(permissions.missing(needed).toTypedArray(), REQUEST_STORAGE)
        }
        viewModel.refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT < 30 || permissions.hasAllFilesAccess()) {
            viewModel.refresh()
        }
    }

    /**
     * The empty state is only for a confirmed, fully-loaded, genuinely empty
     * result. A restricted folder (scoped storage) says so explicitly instead of
     * pretending there is nothing inside it.
     */
    /**
     * One column, always.
     *
     * This used to switch to a three-wide grid whenever libraries were on
     * screen, which put the library tiles side by side and left everything else
     * squashed into a third of the width. The tabs separate the libraries now,
     * so the list is just a list - including the date headings, which a grid
     * could only render as a stray cell.
     */
    private fun applyLayout(gallery: Boolean) {
        val binding = binding ?: return
        if (binding.list.layoutManager !is LinearLayoutManager) {
            binding.list.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun updateEmptyState() {
        val binding = binding ?: return
        val loading = viewModel.loading.value == true
        val rowsEmpty = viewModel.rows.value.isNullOrEmpty()
        binding.empty.isVisible = !loading && rowsEmpty
        binding.empty.text = when (viewModel.state.value ?: DirectoryState.OK) {
            DirectoryState.RESTRICTED -> getString(R.string.files_restricted)
            DirectoryState.MISSING -> getString(R.string.files_missing)
            DirectoryState.EMPTY, DirectoryState.OK -> getString(com.morselink.core.ui.R.string.empty_generic)
        }
    }

    private fun renderSelection() {
        val binding = binding ?: return
        val count = viewModel.selectionSize()
        binding.actionBar.isVisible = count > 0
        // The footer is the one that says what sending will do, so it carries
        // the count and the total size rather than a bare "Send".
        binding.selectionBar.isVisible = count > 0
        val peer = connection.peer?.takeIf { connection.hasSession() }
        binding.btnSend.text = when {
            count == 0 -> getString(R.string.action_send)
            else -> getString(
                R.string.files_send_with_count,
                count,
                Format.bytes(viewModel.selectedBytes()),
            )
        }
        binding.actionSend.text = when {
            count == 0 -> getString(R.string.action_send)
            peer != null -> getString(R.string.files_sending_to, count, peer.name)
            else -> getString(R.string.action_send)
        }
        adapter.notifyDataSetChanged()
        if (count == 0) viewModel.clearSelection()
    }

    /**
     * Hands the selection to the transfer session.
     *
     * With a session already up this sends straight away; without one the files
     * are queued and the transfer screen opens to make the connection. Both
     * orders were asked for - connect first and then choose, or choose and then
     * connect - and both end at the same place.
     */
    private fun sendSelection() {
        val items = viewModel.selectedItems()
        if (items.isEmpty()) return
        connection.pendingOutgoing = items.map { item ->
            TransferableFile(
                id = item.path,
                name = item.name,
                sizeBytes = item.sizeBytes,
                mimeType = item.mimeType,
                uri = item.uri,
                path = item.path,
            )
        }
        val count = items.size
        viewModel.clearSelection()
        renderSelection()
        Toast.makeText(
            requireContext(),
            if (connection.hasSession()) {
                getString(com.morselink.core.ui.R.string.action_send)
            } else {
                getString(R.string.files_queued, count)
            },
            Toast.LENGTH_SHORT,
        ).show()
        findNavController().navigate(Uri.parse("morselink://transfer"))
    }

    private fun shareSelection() {
        val files = viewModel.selectedItems()
        if (files.isEmpty()) return
        val uris = files.map { item ->
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                File(item.path),
            )
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(com.morselink.core.ui.R.string.action_share))) }
            .onFailure { Toast.makeText(requireContext(), "No app can open these files", Toast.LENGTH_SHORT).show() }
        clearSelection()
    }

    private fun deleteSelection() {
        val items = viewModel.selectedItems()
        if (items.isEmpty()) return
        Dialogs.confirm(
            requireContext(),
            getString(com.morselink.core.ui.R.string.action_delete),
            "Delete ${items.size} item(s)?",
            confirmLabel = getString(com.morselink.core.ui.R.string.action_delete),
            destructive = true,
        ) {
            viewModel.delete(items) { removed ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.files_deleted, removed),
                    Toast.LENGTH_SHORT,
                ).show()
                clearSelection()
            }
        }
    }

    private fun renameSelection() {
        val item = viewModel.selectedItems().firstOrNull() ?: return
        Dialogs.input(requireContext(), getString(com.morselink.core.ui.R.string.action_rename), item.name) { newName ->
            viewModel.rename(item, newName) { clearSelection() }
        }
    }

    private fun pickDirectoryAnd(move: Boolean) {
        val items = viewModel.selectedItems()
        if (items.isEmpty()) return
        val path = viewModel.currentPath()
        Dialogs.input(
            requireContext(),
            if (move) getString(R.string.files_move_to) else getString(R.string.files_copy_to),
            path,
            hint = "/sdcard/",
        ) { target ->
            viewModel.moveOrCopy(items, target, move) { clearSelection() }
        }
    }

    private fun compressSelection() {
        val items = viewModel.selectedItems()
        if (items.isEmpty()) return
        viewModel.compress(items) { zip ->
            Toast.makeText(
                requireContext(),
                getString(R.string.files_compressed, zip.name),
                Toast.LENGTH_SHORT,
            ).show()
            clearSelection()
        }
    }

    private fun showProperties() {
        val item = viewModel.selectedItems().firstOrNull() ?: return
        val file = File(item.path)
        Dialogs.confirm(
            requireContext(),
            item.name,
            getString(
                R.string.files_properties,
                item.name,
                Format.bytes(item.sizeBytes),
                Format.fullDate(item.lastModified),
                item.path,
            ).replace("\\n", "\n"),
            confirmLabel = getString(com.morselink.core.ui.R.string.action_ok),
        ) { }
        file.length() // touch to keep the reference meaningful for lint
    }

    private fun clearSelection() {
        viewModel.clearSelection()
        renderSelection()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val REQUEST_STORAGE = 3101
    }
}

/** Smart-category rows and file rows share one list; folders navigate, files select. */
sealed interface FileRow {
    data class Category(val category: SmartCategory, val count: Int) : FileRow
    /** A date-group heading - Today / Yesterday / Earlier. */
    data class Header(val title: String, val bucket: Int) : FileRow
    data class Entry(val item: FileItem) : FileRow

    /**
     * One of the media libraries, offered at the top level as a gallery card.
     *
     * Photos, videos, music and apps are what people open a file manager for,
     * and they were reachable only from the Send tab - buried behind a
     * transfer you had to start first.
     */
    data class Library(val library: MediaLibrary, val count: Int, val cover: FileItem?) : FileRow
}

/** The media libraries offered from the Files tab. */
enum class MediaLibrary { PHOTOS, VIDEOS, MUSIC, APPS }

// Shared names and icons. They used to live privately in the adapter, but the
// view model needs them for the breadcrumb too, and two copies of the same
// wording drift apart.

fun SmartCategory.label(): String = when (this) {
    SmartCategory.DOCUMENTS -> "Documents"
    SmartCategory.EBOOKS -> "Ebooks"
    SmartCategory.APKS -> "APKs"
    SmartCategory.ARCHIVES -> "Archives"
    SmartCategory.LARGE_FILES -> "Large files"
}

fun SmartCategory.subtitle(): String = when (this) {
    SmartCategory.DOCUMENTS -> "Word, Excel, PPT, PDF, etc."
    SmartCategory.EBOOKS -> ".epub, .txt, .pdf"
    SmartCategory.APKS -> "Installed app packages"
    SmartCategory.ARCHIVES -> ".zip, .rar, .7z"
    SmartCategory.LARGE_FILES -> "Files over 50MB"
}

fun MediaLibrary.label(): String = when (this) {
    MediaLibrary.PHOTOS -> "Photos"
    MediaLibrary.VIDEOS -> "Videos"
    MediaLibrary.MUSIC -> "Music"
    MediaLibrary.APPS -> "Apps"
}

fun MediaLibrary.icon(): Int = when (this) {
    MediaLibrary.PHOTOS -> com.morselink.core.ui.R.drawable.ic_photo
    MediaLibrary.VIDEOS -> com.morselink.core.ui.R.drawable.ic_video
    MediaLibrary.MUSIC -> com.morselink.core.ui.R.drawable.ic_music
    MediaLibrary.APPS -> com.morselink.core.ui.R.drawable.ic_app
}
