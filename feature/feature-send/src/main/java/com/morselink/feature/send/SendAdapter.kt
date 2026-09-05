package com.morselink.feature.send

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.core.media.FileItem
import com.morselink.core.media.MediaItem
import com.morselink.core.ui.Format
import com.morselink.feature.send.databinding.ItemMediaGridBinding
import com.morselink.feature.send.databinding.ItemMediaHeaderBinding
import com.morselink.feature.send.databinding.ItemMediaListBinding
import java.util.concurrent.Executors

/**
 * Grid cells for Photos/Videos/Apps, list rows for Music/Files, and full-width
 * date headers on every tab (§14.2).
 */
class SendAdapter(
    private val onToggle: (SendRow) -> Unit,
    private val isSelected: (SendRow) -> Boolean,
    private val onOpenCategory: ((SendRow.Category) -> Unit)? = null,
) : ListAdapter<SendRow, RecyclerView.ViewHolder>(Diff) {

    /** Grid or list, switched in place so the adapter is never recreated. */
    var useGrid: Boolean = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemMediaHeaderBinding.inflate(inflater, parent, false))
            TYPE_GRID -> GridHolder(ItemMediaGridBinding.inflate(inflater, parent, false))
            else -> ListHolder(ItemMediaListBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        val row = getItem(position)
        return when {
            row is SendRow.Header -> TYPE_HEADER
            useGrid && row !is SendRow.Category -> TYPE_GRID
            else -> TYPE_LIST
        }
    }

    /** Headers always span the full width, even inside a grid. */
    fun isHeader(position: Int): Boolean = getItem(position) is SendRow.Header

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is HeaderHolder -> holder.bind(row as SendRow.Header)
            is GridHolder -> holder.bind(row)
            is ListHolder -> holder.bind(row)
        }
    }

    // -------------------------------------------------------------- view holders

    private inner class HeaderHolder(
        private val binding: ItemMediaHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SendRow.Header) {
            binding.title.text = row.label
            binding.count.text = row.count.toString()
            binding.checkMark.visibility =
                if (row.allSelected) android.view.View.VISIBLE else android.view.View.GONE
            binding.checkRing.alpha = if (row.allSelected) 1f else 0.6f
            binding.root.setOnClickListener { onToggle(row) }
        }
    }

    private inner class GridHolder(
        private val binding: ItemMediaGridBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SendRow) {
            resetSelection(binding.check, binding.selectedRing)
            binding.name.text = row.name
            val selected = isSelected(row)
            binding.check.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.selectedRing.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            val duration = (row as? SendRow.Media)?.item?.durationMs ?: 0L
            binding.badge.visibility = if (duration > 0) android.view.View.VISIBLE else android.view.View.GONE
            binding.badge.text = if (duration > 0) Format.duration(duration) else ""
            loadThumb(binding.thumb, row)
            binding.root.setOnClickListener { onToggle(row) }
        }
    }

    private inner class ListHolder(
        private val binding: ItemMediaListBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SendRow) {
            resetSelection(binding.check, null)
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
            when (row) {
                is SendRow.Header -> Unit
                is SendRow.Category -> {
                    binding.title.text = row.category.label()
                    binding.subtitle.text = row.category.subtitle()
                    binding.trailing.text = row.count.toString()
                    binding.thumb.setImageResource(android.R.drawable.ic_menu_agenda)
                    binding.root.setOnClickListener { onOpenCategory?.invoke(row) }
                }
                is SendRow.Media -> {
                    binding.title.text = row.item.displayName
                    binding.subtitle.text = row.item.artist?.takeIf { it.isNotBlank() }
                        ?: row.item.album?.takeIf { it.isNotBlank() }
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
                    loadThumb(binding.thumb, row)
                    binding.root.setOnClickListener { onToggle(row) }
                }
                is SendRow.File -> {
                    binding.title.text = row.file.name
                    binding.subtitle.text = if (row.file.isDirectory) {
                        if (row.file.childCount >= 0) "${row.file.childCount} items" else "Folder"
                    } else Format.bytes(row.file.sizeBytes)
                    binding.trailing.text = row.file.mimeType ?: ""
                    loadThumb(binding.thumb, row)
                    binding.root.setOnClickListener { onToggle(row) }
                }
            }
        }
    }

    private fun resetSelection(check: ImageView, ring: ImageView?) {
        check.visibility = android.view.View.GONE
        ring?.visibility = android.view.View.GONE
    }

    // ------------------------------------------------------------------ artwork

    /**
     * Every row type needs a different source: a photo is a decodable Uri, an APK
     * path is not an image at all, and album art has to be pulled out of the
     * audio file's metadata. Handing all three to Glide is what left the Apps and
     * Music tabs showing nothing but placeholder squares.
     */
    private fun loadThumb(image: ImageView, row: SendRow) {
        // Cancel any load still in flight from the row this holder used to show.
        Glide.with(image).clear(image)
        image.tag = row.key
        when (row) {
            is SendRow.Media -> loadMediaThumb(image, row.item)
            is SendRow.App -> loadAppIcon(image, row)
            is SendRow.File -> loadFileThumb(image, row.file)
            is SendRow.Category -> image.setImageResource(android.R.drawable.ic_menu_agenda)
            is SendRow.Header -> Unit
        }
    }

    private fun loadMediaThumb(image: ImageView, item: MediaItem) {
        val isAudio = item.mimeType?.startsWith("audio") == true ||
            (item.durationMs > 0 && item.mimeType == null)
        if (isAudio) {
            loadAlbumArt(image, item)
            return
        }
        Glide.with(image)
            .load(item.uri)
            .placeholder(com.morselink.core.ui.R.drawable.bg_thumb)
            .error(com.morselink.core.ui.R.drawable.ic_photo)
            .centerCrop()
            .into(image)
    }

    /** Embedded art first, generic music note only when there is none. */
    private fun loadAlbumArt(image: ImageView, item: MediaItem) {
        image.setImageResource(com.morselink.core.ui.R.drawable.ic_music)
        val token = image.tag
        thumbExecutor.execute {
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(image.context, item.uri)
                    retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
            if (bitmap == null) return@execute
            image.post {
                if (image.tag == token) image.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadAppIcon(image: ImageView, row: SendRow.App) {
        image.setImageResource(android.R.drawable.sym_def_app_icon)
        val token = image.tag
        thumbExecutor.execute {
            val drawable = runCatching {
                image.context.packageManager.getApplicationIcon(row.app.packageName)
            }.getOrNull() ?: return@execute
            image.post {
                if (image.tag == token) image.setImageDrawable(drawable)
            }
        }
    }

    private fun loadFileThumb(image: ImageView, file: FileItem) {
        val resource = when {
            file.isDirectory && !file.canRead -> com.morselink.core.ui.R.drawable.ic_blocked
            file.isDirectory -> android.R.drawable.ic_menu_agenda
            file.mimeType?.startsWith("image") == true -> {
                Glide.with(image).load(file.path)
                    .placeholder(com.morselink.core.ui.R.drawable.bg_thumb)
                    .error(com.morselink.core.ui.R.drawable.ic_photo)
                    .centerCrop()
                    .into(image)
                return
            }
            file.mimeType?.startsWith("video") == true -> com.morselink.core.ui.R.drawable.ic_video
            file.mimeType?.startsWith("audio") == true -> com.morselink.core.ui.R.drawable.ic_music
            file.name.endsWith(".apk", true) -> com.morselink.core.ui.R.drawable.ic_app
            file.name.endsWith(".zip", true) || file.name.endsWith(".rar", true) ->
                com.morselink.core.ui.R.drawable.ic_compress
            else -> com.morselink.core.ui.R.drawable.ic_file
        }
        image.setImageResource(resource)
    }

    private object Diff : DiffUtil.ItemCallback<SendRow>() {
        override fun areItemsTheSame(oldItem: SendRow, newItem: SendRow): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: SendRow, newItem: SendRow): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GRID = 1
        const val TYPE_LIST = 2

        /**
         * Icons and album art come off the main thread. Two threads is enough:
         * the work is short and the RecyclerView only shows a screenful at a time.
         */
        val thumbExecutor = Executors.newFixedThreadPool(2)
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
