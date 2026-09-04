package com.morselink.feature.filemanager

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.core.media.FileItem
import com.morselink.core.media.SmartCategory
import com.morselink.core.ui.Dialogs
import com.morselink.core.ui.Format
import com.morselink.feature.filemanager.databinding.FragmentFileManagerBinding
import com.morselink.app.Permissions
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * §14.3 / §14.4 — smart categories on entry, raw folder navigation after that,
 * with the long-press action toolbar for share/delete/rename/move/copy/compress.
 */
@AndroidEntryPoint
class FileManagerFragment : Fragment(R.layout.fragment_file_manager) {

    @Inject
    lateinit var permissions: Permissions

    private val viewModel: FileManagerViewModel by viewModels()

    private var binding: FragmentFileManagerBinding? = null
    private lateinit var adapter: FileAdapter

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

        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submitList(rows)
            binding.empty.isVisible = rows.isEmpty()
        }
        viewModel.breadcrumb.observe(viewLifecycleOwner) { binding.breadcrumb.text = it }
        viewModel.storage.observe(viewLifecycleOwner) { info ->
            binding.storageBar.progress = (info.usedFraction * 100).toInt()
            binding.storageText.text = "${Format.bytes(info.usedBytes)} / ${Format.bytes(info.totalBytes)}"
        }

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

    private fun renderSelection() {
        val binding = binding ?: return
        val count = viewModel.selectionSize()
        binding.actionBar.isVisible = count > 0
        adapter.notifyDataSetChanged()
        if (count == 0) viewModel.clearSelection()
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
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.action_share))) }
            .onFailure { Toast.makeText(requireContext(), "No app can open these files", Toast.LENGTH_SHORT).show() }
        clearSelection()
    }

    private fun deleteSelection() {
        val items = viewModel.selectedItems()
        if (items.isEmpty()) return
        Dialogs.confirm(
            requireContext(),
            getString(R.string.action_delete),
            "Delete ${items.size} item(s)?",
            confirmLabel = getString(R.string.action_delete),
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
        Dialogs.input(requireContext(), getString(R.string.action_rename), item.name) { newName ->
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
            confirmLabel = getString(R.string.action_ok),
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
    data class Entry(val item: FileItem) : FileRow
}
