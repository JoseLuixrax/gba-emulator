package com.joseluixrax.gbaemu.ui.library

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joseluixrax.gbaemu.Prefs
import com.joseluixrax.gbaemu.R
import com.joseluixrax.gbaemu.databinding.ActivityLibraryBinding
import com.joseluixrax.gbaemu.emulator.GameFiles
import com.joseluixrax.gbaemu.emulator.RomLoader
import com.joseluixrax.gbaemu.ui.game.GameActivity
import com.joseluixrax.gbaemu.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Start screen: the games found in the user's ROM folder plus recently played
 * ones. Tap to play, long-press for save management and home screen shortcuts.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: RomAdapter

    /** Result of the last folder scan; rescanned on demand. */
    private var folderRoms: List<RomEntry>? = null
    private var pendingSaveGame: LibraryItem.Game? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistPermission(uri)
            prefs.romFolder = uri.toString()
            folderRoms = null
            refresh()
        }
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistPermission(uri)
            launchGame(uri, RomLoader.displayName(this, uri))
        }
    }

    private val importSave = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = pendingSaveGame
        if (uri != null && game != null) importSaveFile(game, uri)
    }

    private val exportSave = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val game = pendingSaveGame
        if (uri != null && game != null) exportSaveFile(game, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.toolbar.inflateMenu(R.menu.library)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_choose_folder -> pickFolder.launch(null)
                R.id.action_open_file -> pickRom.launch(arrayOf("*/*"))
                R.id.action_refresh -> {
                    folderRoms = null
                    refresh()
                }
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        binding.chooseFolderButton.setOnClickListener { pickFolder.launch(null) }
        binding.openFileButton.setOnClickListener { pickRom.launch(arrayOf("*/*")) }

        adapter = RomAdapter(
            onClick = { launchGame(it.uri, it.name) },
            onLongClick = { view, game -> showGameOptions(view, game) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** "Open with" from a file manager. */
    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        setIntent(Intent(this, LibraryActivity::class.java))
        launchGame(uri, RomLoader.displayName(this, uri))
    }

    private fun refresh() {
        val folder = prefs.romFolder?.let(Uri::parse)
        lifecycleScope.launch {
            var roms = folderRoms
            if (roms == null && folder != null) {
                binding.progress.isVisible = adapter.getItemCount() == 0
                roms = withContext(Dispatchers.IO) { RomScanner.scan(this@LibraryActivity, folder) }
                folderRoms = roms
                binding.progress.isVisible = false
            }
            show(roms.orEmpty())
        }
    }

    private fun show(roms: List<RomEntry>) {
        val items = ArrayList<LibraryItem>()
        val recents = RecentGames.list(this)
        if (recents.isNotEmpty()) {
            items += LibraryItem.Header(getString(R.string.recent_games))
            recents.forEach { items += LibraryItem.Game(it.uri, it.name, 0, recent = true) }
        }
        if (roms.isNotEmpty()) {
            items += LibraryItem.Header(getString(R.string.all_games, roms.size))
            roms.forEach { items += LibraryItem.Game(it.uri, it.name, it.size, recent = false) }
        }
        adapter.submit(items)
        binding.emptyView.isVisible = items.isEmpty()
        binding.list.isVisible = items.isNotEmpty()
    }

    private fun launchGame(uri: Uri, name: String) {
        startActivity(GameActivity.intent(this, uri, name))
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not all providers allow it; the game still opens this time.
        }
    }

    // ---------------------------------------------------------- game options

    private fun showGameOptions(anchor: View, game: LibraryItem.Game) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(0, 1, 0, R.string.play)
        menu.add(0, 2, 1, R.string.import_save)
        menu.add(0, 3, 2, R.string.export_save)
        menu.add(0, 4, 3, R.string.delete_states)
        menu.add(0, 5, 4, R.string.create_shortcut)
        if (game.recent) menu.add(0, 6, 5, R.string.remove_from_recent)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> launchGame(game.uri, game.name)
                2 -> {
                    pendingSaveGame = game
                    importSave.launch(arrayOf("*/*"))
                }
                3 -> {
                    val files = filesFor(game)
                    if (files.saveFile.isFile) {
                        pendingSaveGame = game
                        exportSave.launch("${files.gameKey}.sav")
                    } else {
                        toast(getString(R.string.no_save_yet))
                    }
                }
                4 -> confirmDeleteStates(game)
                5 -> createShortcut(game)
                6 -> {
                    RecentGames.remove(this, game.uri)
                    refresh()
                }
            }
            true
        }
        popup.show()
    }

    private fun filesFor(game: LibraryItem.Game) = GameFiles(this, GameFiles.keyFor(game.name))

    private fun importSaveFile(game: LibraryItem.Game, source: Uri) {
        val files = filesFor(game)
        val doImport = {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        files.ensureDirs()
                        val input = contentResolver.openInputStream(source) ?: return@withContext false
                        val data = input.use { it.readBytes() }
                        // Saves are at most 128 KiB (+ RTC data). Anything bigger is not a save.
                        if (data.isEmpty() || data.size > 256 * 1024) return@withContext false
                        files.saveFile.writeBytes(data)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(getString(if (ok) R.string.save_imported else R.string.save_import_failed))
            }
        }
        if (files.saveFile.isFile) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.import_overwrite_confirm)
                .setPositiveButton(R.string.import_save) { _, _ -> doImport() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            doImport()
        }
    }

    private fun exportSaveFile(game: LibraryItem.Game, target: Uri) {
        val files = filesFor(game)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(target)?.use { out ->
                        files.saveFile.inputStream().use { it.copyTo(out) }
                    } != null
                } catch (_: Exception) {
                    false
                }
            }
            toast(getString(if (ok) R.string.save_exported else R.string.save_export_failed))
        }
    }

    private fun confirmDeleteStates(game: LibraryItem.Game) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_states_confirm, game.name.substringBeforeLast('.')))
            .setPositiveButton(R.string.delete) { _, _ ->
                filesFor(game).deleteStates()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createShortcut(game: LibraryItem.Game) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            toast(getString(R.string.shortcut_unsupported))
            return
        }
        val thumb = filesFor(game).thumbnailFile(GameFiles.AUTO_SLOT)
        val bitmap = if (thumb.isFile) BitmapFactory.decodeFile(thumb.path) else null
        val icon = if (bitmap != null) {
            IconCompat.createWithBitmap(bitmap)
        } else {
            IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        }
        val intent = Intent(this, GameActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(game.uri)
            .putExtra(GameActivity.EXTRA_DISPLAY_NAME, game.name)
        val label = game.name.substringBeforeLast('.')
        val shortcut = ShortcutInfoCompat.Builder(this, "game:" + game.uri)
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
