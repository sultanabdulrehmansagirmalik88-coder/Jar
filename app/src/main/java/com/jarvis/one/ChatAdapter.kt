package com.jarvis.one

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val onLongPress: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.Holder>() {
    private val items = mutableListOf<ChatMessage>()

    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    fun append(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.lastIndex)
    }

    fun updateLast(text: String) {
        val last = items.lastOrNull() ?: return
        items[items.lastIndex] = last.copy(content = text)
        notifyItemChanged(items.lastIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(AppPrefs.textColor(parent.context))
            setPadding(16, 12, 16, 12)
            maxWidth = (parent.resources.displayMetrics.widthPixels * 0.82f).toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        return Holder(FrameLayout(parent.context).apply { addView(view) }, view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        val user = message.role == "user"
        holder.text.text = message.content
        holder.text.setTextColor(if (user) Color.WHITE else AppPrefs.textColor(holder.text.context))
        holder.text.background = GradientDrawable().apply {
            cornerRadius = 22f
            setColor(if (user) Color.rgb(49, 111, 239) else AppPrefs.surfaceColor(holder.text.context))
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (user) Gravity.END else Gravity.START
        ).apply {
            setMargins(if (user) 56 else 12, 5, if (user) 12 else 56, 5)
        }
        holder.text.layoutParams = params
        holder.text.setOnLongClickListener { onLongPress(message); true }
    }

    override fun getItemCount(): Int = items.size

    class Holder(container: FrameLayout, val text: TextView) : RecyclerView.ViewHolder(container)
}
