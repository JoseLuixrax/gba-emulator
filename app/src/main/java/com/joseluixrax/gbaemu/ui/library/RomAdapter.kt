package com.joseluixrax.gbaemu.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ItemHeaderBinding
import com.joseluixrax.gbaemu.databinding.ItemRomBinding
import com.joseluixrax.gbaemu.emulator.GameFiles
import com.joseluixrax.gbaemu.emulator.RomLoader

sealed class LibraryItem {
    data class Header(val title: String) : LibraryItem()
    data class Game(val uri: Uri, val name: String, val size: Long, val recent: Boolean) : LibraryItem()
}

class RomAdapter(
    private val onClick: (LibraryItem.Game) -> Unit,
    private val onLongClick: (View, LibraryItem.Game) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<LibraryItem> = emptyList()

    fun submit(newItems: List<LibraryItem>) {
        items = newItems
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is LibraryItem.Header -> TYPE_HEADER
        is LibraryItem.Game -> TYPE_GAME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            GameHolder(ItemRomBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryItem.Header -> (holder as HeaderHolder).binding.header.text = item.title
            is LibraryItem.Game -> bindGame(holder as GameHolder, item)
        }
    }

    private fun bindGame(holder: GameHolder, item: LibraryItem.Game) {
        val b = holder.binding
        val context = b.root.context
        b.title.text = item.name.substringBeforeLast('.')
        val ext = RomLoader.extensionOf(item.name)
        val system = when (ext) {
            "gb" -> "Game Boy"
            "gbc" -> "Game Boy Color"
            "zip" -> "ZIP"
            else -> "Game Boy Advance"
        }
        b.subtitle.text = if (item.size > 0) {
            context.getString(R.string.rom_subtitle, system, Formatter.formatShortFileSize(context, item.size))
        } else {
            system
        }
        // Show where you left off (automatic save state) when there is one.
        val thumb = GameFiles(context, GameFiles.keyFor(item.name)).thumbnailFile(GameFiles.AUTO_SLOT)
        val bitmap = if (thumb.isFile) BitmapFactory.decodeFile(thumb.path) else null
        if (bitmap != null) {
            b.thumb.setImageBitmap(bitmap)
        } else {
            b.thumb.setImageResource(R.drawable.ic_cartridge)
        }
        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLongClick(it, item)
            true
        }
    }

    private class HeaderHolder(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class GameHolder(val binding: ItemRomBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GAME = 1
    }
}
