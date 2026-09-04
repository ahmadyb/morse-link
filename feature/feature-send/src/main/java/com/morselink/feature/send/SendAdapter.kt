package com.morselink.feature.send

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.core.ui.Format
import com.morselink.feature.send.databinding.ItemMediaGridBinding
import com.morselink.feature.send.databinding.ItemMediaListBinding

/** Grid cells for Photos/Videos/Apps, list rows for Music/Files (§14.2). */
class SendAdapter(
    private val useGrid: Boolean,
    private val onToggle: (SendRow) -> Unit,
    private val isSelected: (SendRow) -> Boolean,
    private val onOpenCategory: ((SendRow.Category) -> Unit)? = null,
) : ListAdapter<SendRow, RecyclerView.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridHolder(ItemMediaGridBinding.inflate(inflater, parent, false))
        } else {
            ListHolder(ItemMediaListBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (useGrid && getItem(position) !is SendRow.Category) TYPE_GRID else TYPE_LIST

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder is GridHolder) holder.bind(row) else (holder as ListHolder).bind(row)
    }

    private inner class GridHolder(
        private val binding: ItemMediaGridBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SendRow) {
            binding.name.text = row.name
            val selected = isSelected(row)
            binding.check.isVisible = selected
            binding.selectedRing.isVisible = selected
            binding.badge.isVisible = (row as? SendRow.Media)?.item?.durationMs?.let { it > 0 } ?: false
            binding.badge.text = (row as? SendRow.Media)?.item?.durationMs
                ?.takeIf { it > 0 }?.let { Format.duration(it) } ?: ""
            loadThumb(binding.thumb, row)
            binding.root.setOnClickListener { onToggle(row) }
        }
    }

    private inner class ListHolder(
        private val binding: ItemMediaListBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SendRow) {
            val selected = isSelected(row)
            binding.check.isVisible = selected
            when (row) {
                is SendRow.Category -> {
                    binding.title.text = row.category.label()
                    binding.subtitle.text = row.category.subtitle()
                    binding.trailing.text = row.count.toString()
                    binding.thumb.setImageResource(android.R.drawable.ic_menu_agenda)
                    binding.root.setOnClickListener { onOpenCategory?.invoke(row) }
                }
                is SendRow.Media -> {
                    binding.title.text = row.item.displayName
                    binding.subtitle.text = row.item.artist
                        ?: row.item.album
                        ?: Format.bytes(row.item.sizeBytes)
                    binding.trailing.text = if (row.item.durationMs > 0) {
                        Format.duration(row.item.durationMs)
                    } else Format.bytes(row.item.sizeBytes)
                    loadThumb(binding.thumb, row)
                    binding.root.setOnClickListener { onToggle(row) }
                }
                is SendRow.App -> {
                    binding.title.text = row.app.label
                    binding.subtitle.text = row.app.packageName
                    binding.trailing.text = Format.bytes(row.app.sizeBytes)
                    binding.thumb.setImageResource(android.R.drawable.sym_def_app_icon)
                    binding.root.setOnClickListener { onToggle(row) }
                }
                is SendRow.File -> {
                    binding.title.text = row.file.name
                    binding.subtitle.text = if (row.file.isDirectory) {
                        "${row.file.childCount} items"
                    } else Format.bytes(row.file.sizeBytes)
                    binding.trailing.text = row.file.mimeType ?: ""
                    binding.thumb.setImageResource(
                        if (row.file.isDirectory) android.R.drawable.ic_menu_agenda
                        else android.R.drawable.ic_menu_save
                    )
                    binding.root.setOnClickListener { onToggle(row) }
                }
            }
        }
    }

    private fun loadThumb(image: ImageView, row: SendRow) {
        val model: Any? = when (row) {
            is SendRow.Media -> row.item.uri
            is SendRow.App -> row.app.apkPath
            is SendRow.File -> row.file.path
            is SendRow.Category -> null
        }
        Glide.with(image)
            .load(model)
            .placeholder(com.morselink.core.ui.R.drawable.bg_thumb)
            .centerCrop()
            .into(image)
    }

    private object Diff : DiffUtil.ItemCallback<SendRow>() {
        override fun areItemsTheSame(oldItem: SendRow, newItem: SendRow): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: SendRow, newItem: SendRow): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_LIST = 1
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

private fun TextView.setVisible(flag: Boolean) {
    visibility = if (flag) View.VISIBLE else View.GONE
}
