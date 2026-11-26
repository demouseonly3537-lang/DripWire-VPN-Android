package cc.dripwire.vpn.support

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import cc.dripwire.vpn.databinding.ItemSupportBubbleAdminBinding
import cc.dripwire.vpn.databinding.ItemSupportBubbleUserBinding
import cc.dripwire.vpn.messages.formatIsoTimestamp

private const val VIEW_TYPE_USER = 0
private const val VIEW_TYPE_ADMIN = 1

class SupportAdapter(private val messages: MutableList<SupportMessage>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class UserViewHolder(val binding: ItemSupportBubbleUserBinding) : RecyclerView.ViewHolder(binding.root)
    class AdminViewHolder(val binding: ItemSupportBubbleAdminBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (messages[position].sender == "admin") VIEW_TYPE_ADMIN else VIEW_TYPE_USER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ADMIN) {
            AdminViewHolder(ItemSupportBubbleAdminBinding.inflate(inflater, parent, false))
        } else {
            UserViewHolder(ItemSupportBubbleUserBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val (bodyView, imageView, timeView) = when (holder) {
            is AdminViewHolder -> Triple(holder.binding.bubbleBodyText, holder.binding.bubbleImage, holder.binding.bubbleTimeText)
            is UserViewHolder -> Triple(holder.binding.bubbleBodyText, holder.binding.bubbleImage, holder.binding.bubbleTimeText)
            else -> return
        }

        if (message.body.isNullOrBlank()) {
            bodyView.visibility = View.GONE
        } else {
            bodyView.visibility = View.VISIBLE
            bodyView.text = message.body
        }

        if (message.imageUrl.isNullOrBlank()) {
            imageView.visibility = View.GONE
        } else {
            imageView.visibility = View.VISIBLE
            // The signed image URL can expire or fail to load — collapse the reserved space
            // instead of leaving a blank box if that happens.
            Glide.with(imageView)
                .load(message.imageUrl)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        imageView.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        imageView.visibility = View.VISIBLE
                        return false
                    }
                })
                .into(imageView)
        }

        timeView.text = formatIsoTimestamp(message.createdAt).substringAfter(' ').ifBlank { message.createdAt }
    }

    override fun getItemCount(): Int = messages.size

    fun replaceAll(newMessages: List<SupportMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun appendAll(newMessages: List<SupportMessage>) {
        val startIndex = messages.size
        messages.addAll(newMessages)
        notifyItemRangeInserted(startIndex, newMessages.size)
    }
}
