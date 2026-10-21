package com.joseluixrax.gbaemu.ui.cheats

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.cheats.Cheat
import com.joseluixrax.gbaemu.cheats.CheatStore
import com.joseluixrax.gbaemu.cheats.CheatType
import com.joseluixrax.gbaemu.databinding.ActivityCheatsBinding
import com.joseluixrax.gbaemu.databinding.DialogCheatBinding
import com.joseluixrax.gbaemu.databinding.ItemCheatBinding
import com.joseluixrax.gbaemu.emulator.NativeBridge

/**
 * Per-game cheat list. Changes are saved immediately; the game picks them up
 * when it resumes.
 */
class CheatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCheatsBinding
    private lateinit var store: CheatStore
    private lateinit var cheats: MutableList<Cheat>
    private val adapter = CheatAdapter()
    private var isGameBoy = false

    /** Cheat formats offered for the current system, as (label, CheatType). */
    private val types: List<Pair<Int, Int>> by lazy {
        if (isGameBoy) {
            listOf(
                R.string.cheat_type_auto to CheatType.AUTO,
                R.string.cheat_type_gameshark to CheatType.GAMESHARK,
                R.string.cheat_type_game_genie to CheatType.GAME_GENIE,
                R.string.cheat_type_raw to CheatType.RAW,
            )
        } else {
            listOf(
                R.string.cheat_type_auto to CheatType.AUTO,
                R.string.cheat_type_gameshark to CheatType.GAMESHARK,
                R.string.cheat_type_action_replay to CheatType.ACTION_REPLAY,
                R.string.cheat_type_codebreaker to CheatType.CODEBREAKER,
                R.string.cheat_type_raw to CheatType.RAW,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val gameKey = intent.getStringExtra(EXTRA_GAME) ?: run {
            finish()
            return
        }
        isGameBoy = intent.getIntExtra(EXTRA_PLATFORM, NativeBridge.PLATFORM_GBA) == NativeBridge.PLATFORM_GB
        store = CheatStore(this, gameKey)
        cheats = store.load()

        binding.toolbar.subtitle = gameKey
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.addButton.setOnClickListener { editCheat(null) }
        updateEmpty()
    }

    private fun persist() {
        store.save(cheats)
        updateEmpty()
    }

    private fun updateEmpty() {
        binding.emptyText.isVisible = cheats.isEmpty()
    }

    /** Add (cheat == null) or edit a cheat. */
    private fun editCheat(cheat: Cheat?) {
        val dialogBinding = DialogCheatBinding.inflate(layoutInflater)
        val labels = types.map { getString(it.first) }
        dialogBinding.typeSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        dialogBinding.formatHelp.setText(if (isGameBoy) R.string.cheat_help_gb else R.string.cheat_help_gba)
        if (cheat != null) {
            dialogBinding.nameInput.setText(cheat.name)
            dialogBinding.codeInput.setText(cheat.code)
            dialogBinding.typeSpinner.setSelection(types.indexOfFirst { it.second == cheat.type }.coerceAtLeast(0))
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (cheat == null) R.string.add_cheat else R.string.edit_cheat)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val code = dialogBinding.codeInput.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) return@setPositiveButton
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                    .ifEmpty { getString(R.string.cheat_default_name, cheats.size + 1) }
                val type = types[dialogBinding.typeSpinner.selectedItemPosition.coerceAtLeast(0)].second
                if (cheat == null) {
                    cheats.add(Cheat(name, code, type, enabled = true))
                    adapter.notifyItemInserted(cheats.size - 1)
                } else {
                    cheat.name = name
                    cheat.code = code
                    cheat.type = type
                    adapter.notifyItemChanged(cheats.indexOf(cheat))
                }
                persist()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (cheat != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                val index = cheats.indexOf(cheat)
                if (index >= 0) {
                    cheats.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    persist()
                }
            }
        }
        builder.show()
    }

    private inner class CheatAdapter : RecyclerView.Adapter<CheatHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CheatHolder(ItemCheatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = cheats.size

        override fun onBindViewHolder(holder: CheatHolder, position: Int) {
            val cheat = cheats[position]
            val b = holder.binding
            b.cheatName.text = cheat.name
            b.cheatCode.text = cheat.code.lines().joinToString("  ·  ")
            b.cheatEnabled.setOnCheckedChangeListener(null)
            b.cheatEnabled.isChecked = cheat.enabled
            b.cheatEnabled.setOnCheckedChangeListener { _, checked ->
                cheat.enabled = checked
                persist()
            }
            b.root.setOnClickListener { editCheat(cheat) }
        }
    }

    private class CheatHolder(val binding: ItemCheatBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val EXTRA_GAME = "game"
        private const val EXTRA_PLATFORM = "platform"

        fun intent(context: Context, gameKey: String, platform: Int): Intent =
            Intent(context, CheatsActivity::class.java)
                .putExtra(EXTRA_GAME, gameKey)
                .putExtra(EXTRA_PLATFORM, platform)
    }
}
