package com.joseluixrax.gbaemu.ui.game

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ItemStateSlotBinding
import com.joseluixrax.gbaemu.emulator.GameFiles
import java.util.Date

/** Grid of save state slots with thumbnails, for saving or loading. */
class SaveStateDialog(
    private val context: Context,
    private val files: GameFiles,
    private val saving: Boolean,
    private val onSlotChosen: (Int) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var chosen = false

    fun show() {
        val slots = buildList {
            if (!saving) add(GameFiles.AUTO_SLOT)
            addAll(1..GameFiles.SLOT_COUNT)
        }
        val recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = SlotAdapter(slots)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (saving) R.string.save_state else R.string.load_state)
            .setView(recycler)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!chosen) onDismiss() }
            .show()
    }

    private fun choose(slot: Int) {
        chosen = true
        dialog?.dismiss()
        onSlotChosen(slot)
    }

    private inner class SlotAdapter(private val slots: List<Int>) : RecyclerView.Adapter<SlotHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SlotHolder(ItemStateSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = slots.size

        override fun onBindViewHolder(holder: SlotHolder, position: Int) {
            val slot = slots[position]
            val stateFile = files.stateFile(slot)
            val exists = stateFile.isFile
            val b = holder.binding
            b.slotTitle.text = if (slot == GameFiles.AUTO_SLOT) {
                context.getString(R.string.slot_auto)
            } else {
                context.getString(R.string.slot_number, slot)
            }
            b.slotDate.text = if (exists) {
                val date = Date(stateFile.lastModified())
                DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
            } else {
                context.getString(R.string.slot_empty)
            }
            val thumb = files.thumbnailFile(slot)
            if (exists && thumb.isFile) {
                b.slotImage.setImageBitmap(BitmapFactory.decodeFile(thumb.path))
            } else {
                b.slotImage.setImageDrawable(null)
            }
            b.root.alpha = if (exists || saving) 1f else 0.5f
            b.root.setOnClickListener {
                when {
                    saving || exists -> choose(slot)
                }
            }
            b.root.setOnLongClickListener {
                if (!exists) return@setOnLongClickListener false
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.delete_state_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        stateFile.delete()
                        thumb.delete()
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    private class SlotHolder(val binding: ItemStateSlotBinding) : RecyclerView.ViewHolder(binding.root)
}
