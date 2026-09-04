package com.morselink.feature.transfer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.morselink.core.ui.Format
import com.morselink.core.transfer.model.TransferProgress
import com.morselink.core.transfer.model.TransferStatus
import com.morselink.feature.transfer.databinding.ItemTransferBinding

sealed interface TransferRow {
    data class Header(val title: String) : TransferRow
    data class Item(val progress: TransferProgress) : TransferRow
}

class TransferAdapter : ListAdapter<TransferRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is TransferRow.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(ItemTransferBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemTransferBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is TransferRow.Header -> (holder as HeaderHolder).bind(row)
            is TransferRow.Item -> (holder as ItemHolder).bind(row)
        }
    }

    class HeaderHolder(binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        private val name = binding.name
        private val progress = binding.progress
        private val percent = binding.percent
        private val meta = binding.meta
        private val icon = binding.icon

        fun bind(row: TransferRow.Header) {
            name.text = row.title
            meta.text = ""
            percent.text = ""
            progress.visibility = android.view.View.GONE
            icon.visibility = android.view.View.GONE
        }
    }

    class ItemHolder(private val binding: ItemTransferBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: TransferRow.Item) {
            val progress = row.progress
            binding.icon.visibility = android.view.View.VISIBLE
            binding.icon.setImageResource(
                if (progress.direction == TransferDirection.INCOMING) com.morselink.core.ui.R.drawable.ic_download
                else com.morselink.core.ui.R.drawable.ic_upload
            )
            binding.progress.visibility = android.view.View.VISIBLE
            binding.name.text = progress.name
            binding.percent.text = "${(progress.fraction * 100).toInt()}%"
            binding.progress.progress = (progress.fraction * 100).toInt()
            binding.meta.text = when (progress.status) {
                TransferStatus.COMPLETED -> "Done · ${Format.bytes(progress.sizeBytes)}"
                TransferStatus.FAILED -> progress.errorMessage ?: "Failed"
                TransferStatus.CANCELLED -> "Cancelled"
                else -> "${Format.bytes(progress.bytesTransferred)} of ${Format.bytes(progress.sizeBytes)}" +
                    progress.bytesPerSecond.takeIf { it > 0 }?.let { " · ${Format.speed(it)}" }.orEmpty()
            }
            val color = when (progress.status) {
                TransferStatus.COMPLETED -> com.morselink.core.ui.R.color.success
                TransferStatus.FAILED -> com.morselink.core.ui.R.color.error
                TransferStatus.CANCELLED -> com.morselink.core.ui.R.color.textSecondary
                else -> com.morselink.core.ui.R.color.sending
            }
            binding.percent.setTextColor(
                androidx.core.content.ContextCompat.getColor(binding.root.context, color)
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<TransferRow>() {
        override fun areItemsTheSame(oldItem: TransferRow, newItem: TransferRow): Boolean = when {
            oldItem is TransferRow.Header && newItem is TransferRow.Header ->
                oldItem.title == newItem.title
            oldItem is TransferRow.Item && newItem is TransferRow.Item ->
                oldItem.progress.fileId == newItem.progress.fileId
            else -> false
        }

        override fun areContentsTheSame(oldItem: TransferRow, newItem: TransferRow): Boolean =
            oldItem == newItem
    }
}
