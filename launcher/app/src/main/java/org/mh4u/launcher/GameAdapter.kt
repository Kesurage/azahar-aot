package org.mh4u.launcher

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class GameAdapter(private val activity: MainActivity) :
    RecyclerView.Adapter<GameAdapter.VH>() {

    private var items: List<Game> = emptyList()

    fun submit(list: List<Game>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val game = items[position]
        holder.title.text = game.title
        holder.subtitle.text = android.text.format.Formatter.formatShortFileSize(
            holder.itemView.context, game.sizeBytes
        )
        holder.cover.setImageBitmap(null)
        activity.loadCover(holder.cover, game)
        holder.itemView.setOnClickListener { activity.onGameTap(game) }
        holder.itemView.setOnLongClickListener {
            activity.onGameLongTap(game)
            true
        }
    }
}
