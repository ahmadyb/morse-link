package com.morselink.feature.filemanager

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.morselink.core.ui.Format
import com.morselink.feature.filemanager.databinding.ItemFileBinding

class FileAdapter(
    private val onClick: (FileRow) -> Unit,
    private val onLongClick: (com.morselink.core.media.FileItem) -> Boolean,
    private val isSelected: (com.morselink.core.media.FileItem) -> Boolean,
) : ListAdapter<FileRow, FileAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: FileRow) {
            when (row) {
                is FileRow.Category -> {
                    binding.name.text = row.category.label()
                    binding.meta.text = row.category.subtitle()
                    binding.size.text = row.count.toString()
                    binding.icon.setImageResource(android.R.drawable.ic_menu_agenda)
                    binding.root.setOnClickListener { onClick(row) }
                    binding.root.setOnLongClickListener { false }
                }
                is FileRow.Entry -> {
                    val item = row.item
                    binding.name.text = item.name
                    binding.meta.text = if (item.isDirectory) {
                        "${item.childCount} items"
                    } else Format.fullDate(item.lastModified)
                    binding.size.text = if (item.isDirectory) "" else Format.bytes(item.sizeBytes)
                    binding.icon.iconFor(item)
                    val selected = isSelected(item)
                    binding.root.isSelected = selected
                    binding.root.setOnClickListener { onClick(row) }
                    binding.root.setOnLongClickListener { onLongClick(item) }
                }
            }
        }
    }

    private fun ImageView.iconFor(item: com.morselink.core.media.FileItem) {
        val resource = when {
            item.isDirectory -> android.R.drawable.ic_menu_agenda
            item.mimeType?.startsWith("image") == true -> com.morselink.core.ui.R.drawable.ic_photo
            item.mimeType?.startsWith("video") == true -> com.morselink.core.ui.R.drawable.ic_video
            item.mimeType?.startsWith("audio") == true -> com.morselink.core.ui.R.drawable.ic_music
            item.name.endsWith(".apk", true) -> com.morselink.core.ui.R.drawable.ic_app
            item.name.endsWith(".zip", true) || item.name.endsWith(".rar", true) ->
                com.morselink.core.ui.R.drawable.ic_compress
            else -> com.morselink.core.ui.R.drawable.ic_file
        }
        setImageResource(resource)
    }

    private object Diff : DiffUtil.ItemCallback<FileRow>() {
        override fun areItemsTheSame(oldItem: FileRow, newItem: FileRow): Boolean = when {
            oldItem is FileRow.Category && newItem is FileRow.Category ->
                oldItem.category == newItem.category
            oldItem is FileRow.Entry && newItem is FileRow.Entry ->
                oldItem.item.path == newItem.item.path
            else -> false
        }

        override fun areContentsTheSame(oldItem: FileRow, newItem: FileRow): Boolean =
            oldItem == newItem
    }
}

private fun com.morselink.core.media.SmartCategory.label(): String = when (this) {
    com.morselink.core.media.SmartCategory.DOCUMENTS -> "Documents"
    com.morselink.core.media.SmartCategory.EBOOKS -> "Ebooks"
    com.morselink.core.media.SmartCategory.APKS -> "APKs"
    com.morselink.core.media.SmartCategory.ARCHIVES -> "Archives"
    com.morselink.core.media.SmartCategory.LARGE_FILES -> "Large files"
}

private fun com.morselink.core.media.SmartCategory.subtitle(): String = when (this) {
    com.morselink.core.media.SmartCategory.DOCUMENTS -> "Word, Excel, PPT, PDF, etc."
    com.morselink.core.media.SmartCategory.EBOOKS -> ".epub, .txt, .pdf"
    com.morselink.core.media.SmartCategory.APKS -> "Installed app packages"
    com.morselink.core.media.SmartCategory.ARCHIVES -> ".zip, .rar, .7z"
    com.morselink.core.media.SmartCategory.LARGE_FILES -> "Files over 50MB"
}
