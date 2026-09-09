package com.morselink.feature.settings

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.core.data.db.TransferEntity
import com.morselink.feature.settings.databinding.ItemHistoryBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HistoryAdapter(
    private val onOpen: (com.morselink.core.data.db.TransferEntity) -> Unit,
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is HistoryRow.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as Holder).bind(getItem(position))
    }

    inner class Holder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRow) {
            when (row) {
                is HistoryRow.Header -> {
                    binding.name.text = row.label
                    binding.meta.text = ""
                    binding.open.visibility = android.view.View.GONE
                    binding.icon.visibility = android.view.View.GONE
                }
                is HistoryRow.Entry -> {
                    binding.icon.visibility = android.view.View.VISIBLE
                    binding.open.visibility = android.view.View.VISIBLE
                    binding.name.text = row.item.name
                    binding.meta.text = row.meta
                    loadThumb(binding.icon, row.item)
                    binding.open.text = if (row.item.mimeType == "application/vnd.android.package-archive") {
                        binding.root.context.getString(com.morselink.core.ui.R.string.action_install)
                    } else binding.root.context.getString(com.morselink.core.ui.R.string.action_open)
                    binding.root.setOnClickListener { onOpen(row.item) }
                    binding.open.setOnClickListener { onOpen(row.item) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ artwork

    /**
     * Video frames have to be pulled out of the file by hand: Glide will not
     * decode a thumbnail from an arbitrary path on older Android, so without
     * this the video rows stayed blank.
     *
     * A recycled holder can still be carrying a load from the row it used to
     * show, so every path starts by cancelling and by stamping the view with a
     * token that async results are checked against before they are applied.
     */
    private fun loadThumb(image: ImageView, item: TransferEntity) {
        Glide.with(image).clear(image)
        val token = item.id
        image.tag = token

        val path = item.localPath
        val mime = item.mimeType.orEmpty()

        when {
            mime.startsWith("image/") -> {
                if (path.isNullOrBlank()) {
                    image.setImageResource(com.morselink.core.ui.R.drawable.ic_photo)
                    image.scaleType = ImageView.ScaleType.CENTER
                    return
                }
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(image)
                    .load(path)
                    .placeholder(com.morselink.core.ui.R.drawable.bg_thumb)
                    .error(com.morselink.core.ui.R.drawable.ic_photo)
                    .centerCrop()
                    .into(image)
            }

            mime.startsWith("video/") -> {
                image.setImageResource(com.morselink.core.ui.R.drawable.ic_video)
                image.scaleType = ImageView.ScaleType.CENTER
                if (path.isNullOrBlank()) return
                thumbExecutor.execute {
                    val frame = runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            retriever.getFrameAtTime(1_000_000L)
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.getOrNull() ?: return@execute
                    image.post {
                        if (image.tag != token) return@post
                        image.scaleType = ImageView.ScaleType.CENTER_CROP
                        image.setImageBitmap(frame)
                    }
                }
            }

            mime.startsWith("audio/") -> {
                image.setImageResource(com.morselink.core.ui.R.drawable.ic_music)
                image.scaleType = ImageView.ScaleType.CENTER
            }

            mime == "application/vnd.android.package-archive" -> {
                image.setImageResource(com.morselink.core.ui.R.drawable.ic_app)
                image.scaleType = ImageView.ScaleType.CENTER
            }

            mime == "application/pdf" -> {
                image.setImageResource(com.morselink.core.ui.R.drawable.ic_file)
                image.scaleType = ImageView.ScaleType.CENTER
            }

            else -> {
                image.setImageResource(com.morselink.core.ui.R.drawable.ic_file)
                image.scaleType = ImageView.ScaleType.CENTER
            }
        }
    }

    private companion object {
        /** Decoding video frames is too slow for the main thread. */
        val thumbExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    }

    private object Diff : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean = when {
            oldItem is HistoryRow.Header && newItem is HistoryRow.Header -> oldItem.label == newItem.label
            oldItem is HistoryRow.Entry && newItem is HistoryRow.Entry -> oldItem.item.id == newItem.item.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
            oldItem == newItem
    }
}
