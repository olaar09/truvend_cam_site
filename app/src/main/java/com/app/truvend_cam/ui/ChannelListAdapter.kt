package com.app.truvend_cam.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.app.truvend_cam.data.ChannelInfo
import com.app.truvend_cam.databinding.ItemChannelBinding

class ChannelListAdapter(
    private val onClick: (ChannelInfo) -> Unit,
) : RecyclerView.Adapter<ChannelListAdapter.Holder>() {

    private val items = mutableListOf<ChannelInfo>()

    fun submit(list: List<ChannelInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelInfo) {
            binding.channelName.text = item.displayName
            val res = buildString {
                append("ID ${item.streamingChannelId} · ${item.streamType.label}")
                item.codec?.let { append(" · $it") }
                if (item.width != null && item.height != null) {
                    append(" · ${item.width}x${item.height}")
                }
            }
            binding.channelMeta.text = res
            binding.root.isFocusable = true
            binding.root.isClickable = true
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
