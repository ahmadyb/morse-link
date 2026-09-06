package com.morselink.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.morselink.feature.settings.databinding.ItemHistoryBinding

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
                    binding.open.text = if (row.item.mimeType == "application/vnd.android.package-archive") {
                        binding.root.context.getString(com.morselink.core.ui.R.string.action_install)
                    } else binding.root.context.getString(com.morselink.core.ui.R.string.action_open)
                    binding.root.setOnClickListener { onOpen(row.item) }
                    binding.open.setOnClickListener { onOpen(row.item) }
                }
            }
        }
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
