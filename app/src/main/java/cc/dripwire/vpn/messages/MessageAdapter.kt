package cc.dripwire.vpn.messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cc.dripwire.vpn.databinding.ItemMessageBinding

class MessageAdapter(
    private val messages: MutableList<AppMessage>,
    private val onClick: (AppMessage) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        holder.binding.messageBodyText.text = message.body
        holder.binding.messageTimeText.text = formatIsoTimestamp(message.createdAt)
        holder.binding.unreadDot.visibility = if (message.read) View.INVISIBLE else View.VISIBLE
        holder.binding.root.setOnClickListener { onClick(message) }
    }

    override fun getItemCount(): Int = messages.size

    fun markRead(id: Long) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0 && !messages[index].read) {
            messages[index].read = true
            notifyItemChanged(index)
        }
    }
}
