package com.morselink.feature.filemanager

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.core.ui.Format
import com.morselink.feature.filemanager.databinding.ItemFileBinding
import com.morselink.feature.filemanager.databinding.ItemGalleryCardBinding

private const val TYPE_CARD = 1
private const val TYPE_ROW = 2

class FileAdapter(
    private val onClick: (FileRow) -> Unit,
    private val onLongClick: (com.morselink.core.media.FileItem) -> Boolean,
    private val isSelected: (com.morselink.core.media.FileItem) -> Boolean,
) : ListAdapter<FileRow, RecyclerView.ViewHolder>(Diff) {

    /** When true, files are laid out as gallery tiles instead of rows. */
    var useGrid: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int =
        if (isCard(getItem(position))) TYPE_CARD else TYPE_ROW

    private fun isCard(row: FileRow): Boolean = when (row) {
        is FileRow.Library -> true
        is FileRow.Entry -> useGrid
        is FileRow.Category -> false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CARD) {
            CardHolder(ItemGalleryCardBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemFileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is CardHolder -> holder.bind(row)
            is RowHolder -> holder.bind(row)
        }
    }

    // ------------------------------------------------------------ gallery tile

    inner class CardHolder(private val binding: ItemGalleryCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: FileRow) {
            // Recycled tiles arrive with the previous row's thumbnail, badge and
            // tick still attached, so clear all of it before binding.
            binding.check.visibility = android.view.View.GONE
            binding.badge.visibility = android.view.View.GONE
            binding.badge.text = ""
            binding.meta.text = ""
            binding.root.isSelected = false
            binding.root.setOnLongClickListener(null)
            Glide.with(binding.thumb).clear(binding.thumb)

            when (row) {
                is FileRow.Library -> {
                    binding.name.text = row.library.label()
                    binding.meta.text = if (row.count > 0) "${row.count}" else ""
                    binding.thumb.setImageResource(row.library.icon())
                    binding.thumb.scaleType = android.widget.ImageView.ScaleType.CENTER
                    val cover = row.cover
                    if (cover != null && cover.mimeType?.startsWith("image") == true) {
                        loadThumb(binding.thumb, cover)
                        binding.thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                    binding.root.setOnClickListener { onClick(row) }
                }
                is FileRow.Entry -> {
                    val item = row.item
                    binding.name.text = item.name
                    binding.meta.text = if (item.isDirectory) {
                        if (item.childCount >= 0) "${item.childCount} items" else "Folder"
                    } else Format.bytes(item.sizeBytes)
                    binding.thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    loadThumb(binding.thumb, item)
                    if (item.isDirectory) binding.badge.apply {
                        text = binding.meta.text
                        visibility = android.view.View.VISIBLE
                    }
                    val selected = isSelected(item)
                    binding.root.isSelected = selected
                    binding.check.visibility =
                        if (selected) android.view.View.VISIBLE else android.view.View.GONE
                    binding.root.setOnClickListener { onClick(row) }
                    binding.root.setOnLongClickListener { onLongClick(item) }
                }
                is FileRow.Category -> Unit
            }
        }
    }

    /**
     * An APK shows the icon of the app inside it, not a generic "file" glyph -
     * a folder of APKs is otherwise a column of identical shapes.
     *
     * Reading it means opening the archive, so it happens off the main thread
     * and is keyed to the row: a recycled view is simply left alone.
     */
    private val apkIcons = ConcurrentHashMap<String, Drawable?>()
    private val iconExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadApkIcon(image: ImageView, path: String) {
        image.setTag(com.morselink.feature.filemanager.R.id.tag_icon_path, path)
        val cached = apkIcons[path]
        if (cached != null) {
            image.setImageDrawable(cached)
            return
        }
        image.setImageResource(com.morselink.core.ui.R.drawable.ic_app)
        if (apkIcons.containsKey(path)) return
        iconExecutor.execute {
            val icon = runCatching {
                val pm = image.context.packageManager
                val info = pm.getPackageArchiveInfo(path, PackageManager.GET_META_DATA)
                info?.applicationInfo?.apply { sourceDir = path; publicSourceDir = path }
                    ?.loadIcon(pm)
            }.getOrNull()
            apkIcons[path] = icon
            if (icon == null) return@execute
            mainHandler.post {
                if (image.getTag(com.morselink.feature.filemanager.R.id.tag_icon_path) == path) {
                    image.setImageDrawable(icon)
                }
            }
        }
    }

    private fun isApk(item: com.morselink.core.media.FileItem): Boolean =
        !item.isDirectory && (item.name.endsWith(".apk", true) ||
            item.mimeType == "application/vnd.android.package-archive")

    /**
     * A real thumbnail where the device can produce one. Photos and videos come
     * from MediaStore via Glide; APKs from their own package; anything else
     * falls back to a type icon, which is what the row view always showed.
     */
    private fun loadThumb(image: ImageView, item: com.morselink.core.media.FileItem) {
        val uri = item.uri
        if (uri != null && (item.mimeType?.startsWith("image") == true ||
                item.mimeType?.startsWith("video") == true)
        ) {
            Glide.with(image)
                .load(uri)
                .placeholder(com.morselink.core.ui.R.drawable.bg_thumb)
                .error(com.morselink.core.ui.R.drawable.ic_photo)
                .centerCrop()
                .into(image)
        } else if (isApk(item) && item.path.isNotBlank()) {
            loadApkIcon(image, item.path)
        } else {
            image.setImageResource(iconFor(item))
        }
    }

    // --------------------------------------------------------------- file row

    inner class RowHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: FileRow) {
            // Reset every mutable property first: a recycled holder arrives with
            // the previous row's text, icon and selection still attached.
            binding.size.text = ""
            binding.root.isSelected = false
            binding.root.setOnLongClickListener(null)
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
                        if (item.childCount >= 0) "${item.childCount} items" else "Folder"
                    } else Format.fullDate(item.lastModified)
                    binding.size.text = if (item.isDirectory) "" else Format.bytes(item.sizeBytes)
                    if (isApk(item) && item.path.isNotBlank()) {
                        loadApkIcon(binding.icon, item.path)
                    } else {
                        binding.icon.setImageResource(iconFor(item))
                    }
                    val selected = isSelected(item)
                    binding.root.isSelected = selected
                    binding.root.setOnClickListener { onClick(row) }
                    binding.root.setOnLongClickListener { onLongClick(item) }
                }
                is FileRow.Library -> Unit
            }
        }
    }

    private fun iconFor(item: com.morselink.core.media.FileItem): Int = when {
        item.isDirectory && !item.canRead -> com.morselink.core.ui.R.drawable.ic_blocked
        item.isDirectory -> android.R.drawable.ic_menu_agenda
        item.mimeType?.startsWith("image") == true -> com.morselink.core.ui.R.drawable.ic_photo
        item.mimeType?.startsWith("video") == true -> com.morselink.core.ui.R.drawable.ic_video
        item.mimeType?.startsWith("audio") == true -> com.morselink.core.ui.R.drawable.ic_music
        item.name.endsWith(".apk", true) -> com.morselink.core.ui.R.drawable.ic_app
        item.name.endsWith(".zip", true) || item.name.endsWith(".rar", true) ->
            com.morselink.core.ui.R.drawable.ic_compress
        else -> com.morselink.core.ui.R.drawable.ic_file
    }

    private fun ImageView.iconFor(item: com.morselink.core.media.FileItem) {
        val resource = when {
            item.isDirectory && !item.canRead -> com.morselink.core.ui.R.drawable.ic_blocked
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
            oldItem is FileRow.Library && newItem is FileRow.Library ->
                oldItem.library == newItem.library
            oldItem is FileRow.Entry && newItem is FileRow.Entry ->
                oldItem.item.path == newItem.item.path
            else -> false
        }

        override fun areContentsTheSame(oldItem: FileRow, newItem: FileRow): Boolean =
            oldItem == newItem
    }
}

// label() and subtitle() now live in FileManagerFragment.kt, shared with the
// view model, which needs the same words for the breadcrumb.
