package com.wolf.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val time: String
) {
    enum class Sender { USER, WOLF, SYSTEM }
}

class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.Holder>() {

    class Holder(
        val wrapper: FrameLayout,
        val card: LinearLayout,
        val headerView: TextView,
        val bodyView: TextView,
        val timeView: TextView
    ) : RecyclerView.ViewHolder(wrapper)

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val wrapper = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 5)
                bottomMargin = dp(ctx, 5)
            }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 8))
            elevation = dp(ctx, 2).toFloat()
        }

        val headerView = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 3))
        }

        val bodyView = TextView(ctx).apply {
            textSize = 15.5f
            setLineSpacing(0f, 1.28f)
        }

        val timeView = TextView(ctx).apply {
            textSize = 10f
            setPadding(0, dp(ctx, 4), 0, 0)
            gravity = Gravity.END
        }

        card.addView(headerView)
        card.addView(bodyView)
        card.addView(timeView)
        wrapper.addView(card)

        return Holder(wrapper, card, headerView, bodyView, timeView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.wrapper.context
        val maxBubbleWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()

        val bg = GradientDrawable()
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        when (item.sender) {
            ChatMessage.Sender.USER -> {
                lp.gravity = Gravity.START // In RTL, START is Right side
                lp.marginEnd = dp(ctx, 36)
                bg.setColor(Color.parseColor("#0284C7"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#38BDF8"))
                bg.cornerRadii = floatArrayOf(
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(),
                    dp(ctx, 4).toFloat(), dp(ctx, 4).toFloat(),
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(),
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat()
                )
                holder.headerView.text = "شما"
                holder.headerView.setTextColor(Color.parseColor("#BAE6FD"))
                holder.bodyView.setTextColor(Color.WHITE)
                holder.bodyView.maxWidth = maxBubbleWidth
                holder.timeView.setTextColor(Color.parseColor("#E0F2FE"))
            }

            ChatMessage.Sender.WOLF -> {
                lp.gravity = Gravity.END // In RTL, END is Left side
                lp.marginStart = dp(ctx, 36)
                bg.setColor(Color.parseColor("#111827"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#1E293B"))
                bg.cornerRadii = floatArrayOf(
                    dp(ctx, 4).toFloat(), dp(ctx, 4).toFloat(),
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(),
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(),
                    dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat()
                )
                holder.headerView.text = "WOLF AI"
                holder.headerView.setTextColor(Color.parseColor("#38BDF8"))
                holder.bodyView.setTextColor(Color.parseColor("#F1F5F9"))
                holder.bodyView.maxWidth = maxBubbleWidth
                holder.timeView.setTextColor(Color.parseColor("#64748B"))
            }

            ChatMessage.Sender.SYSTEM -> {
                lp.gravity = Gravity.CENTER_HORIZONTAL
                bg.setColor(Color.parseColor("#1E293B"))
                bg.setStroke(dp(ctx, 1), Color.parseColor("#334155"))
                bg.cornerRadius = dp(ctx, 12).toFloat()
                holder.headerView.text = "اعلان سیستم"
                holder.headerView.setTextColor(Color.parseColor("#FBBF24"))
                holder.bodyView.setTextColor(Color.parseColor("#CBD5E1"))
                holder.bodyView.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.88f).toInt()
                holder.timeView.setTextColor(Color.parseColor("#64748B"))
            }
        }

        holder.card.layoutParams = lp
        holder.card.background = bg
        holder.bodyView.text = item.text
        holder.timeView.text = item.time

        holder.card.setOnLongClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Wolf Message", item.text))
            Toast.makeText(ctx, "متن کپی شد", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun addMessage(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun clearAll() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }
}
