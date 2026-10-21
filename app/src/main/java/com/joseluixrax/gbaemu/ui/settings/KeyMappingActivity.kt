package com.joseluixrax.gbaemu.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ActivityKeyMappingBinding
import com.joseluixrax.gbaemu.databinding.ItemKeyMappingBinding
import com.joseluixrax.gbaemu.ui.game.InputAction
import com.joseluixrax.gbaemu.ui.game.InputMapping

/** Assign gamepad / keyboard buttons: tap an action, then press a button. */
class KeyMappingActivity : AppCompatActivity() {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val adapter = MappingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityKeyMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.key_mapping)
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_reset_keys) {
                InputMapping.resetAll(prefs)
                @Suppress("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
            }
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    private fun capture(action: InputAction, position: Int) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(action.label)
            .setMessage(R.string.press_a_button)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.clear_mapping) { _, _ ->
                InputMapping.clear(prefs, action)
                adapter.notifyItemChanged(position)
            }
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            // Back cancels; everything else (gamepad buttons included) is captured.
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) {
                InputMapping.setKey(prefs, action, keyCode)
                @Suppress("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
                d.dismiss()
            }
            true
        }
        dialog.show()
    }

    private inner class MappingAdapter : RecyclerView.Adapter<MappingHolder>() {
        private val actions = InputAction.entries

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MappingHolder(ItemKeyMappingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = actions.size

        override fun onBindViewHolder(holder: MappingHolder, position: Int) {
            val action = actions[position]
            val keys = InputMapping.keysFor(prefs, action)
            holder.binding.actionName.setText(action.label)
            holder.binding.actionKeys.text = if (keys.isEmpty()) {
                getString(R.string.not_assigned)
            } else {
                keys.joinToString(", ") { InputMapping.keyName(it) }
            }
            holder.binding.root.setOnClickListener { capture(action, holder.bindingAdapterPosition) }
        }
    }

    private class MappingHolder(val binding: ItemKeyMappingBinding) : RecyclerView.ViewHolder(binding.root)
}
