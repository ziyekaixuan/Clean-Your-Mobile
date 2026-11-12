package com.cleanyourmobile

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    data class Row(
        val id: String,
        val entry: Db.Pkg,
        val priority: Priority,
        val blockedBecause: String?,
        val alreadyOff: Boolean,
        var selected: Boolean = false,
        /** Installed but absent from the database: shown, never offered. */
        val unrated: Boolean = false,
    ) {
        val actionable: Boolean get() = blockedBecause == null && !alreadyOff

        /**
         * Name and id, lowercased once at construction. The search filter used
         * to lowercase both on every keystroke for every row, and listing
         * unrated packages roughly tripled how many rows that is. Declared in
         * the body rather than the constructor so it stays out of equals().
         */
        val searchKey: String = (entry.name + " " + id).lowercase()
    }

    private lateinit var db: Db
    private lateinit var brand: Brand
    private lateinit var backup: Backup

    private lateinit var deviceName: TextView
    private lateinit var deviceSub: TextView
    private lateinit var brandNotes: TextView
    private lateinit var shizukuCard: MaterialCardView
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuAction: MaterialButton
    private lateinit var summary: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var listView: RecyclerView
    private lateinit var filters: ChipGroup
    private lateinit var searchInput: TextInputEditText
    private lateinit var actionBar: MaterialCardView
    private lateinit var selectedCount: TextView
    private lateinit var runButton: MaterialButton

    private val all = mutableListOf<Row>()
    private val shown = mutableListOf<Row>()
    private val adapter = Adapter()
    private val main = Handler(Looper.getMainLooper())

    private var filter = Filter.SUGGESTED
    private var query = ""

    /** Matches scripts/debloat.sh. AGENTS.md: not six, not "just this once". */
    private val batchSize = 5
    private val maxConsecutiveFailures = 3

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        bindViews()

        db = try {
            Db.load(this)
        } catch (t: Throwable) {
            fatal(getString(R.string.fatal_cannot_start, t.message))
            return
        }
        if (Guard.guardUnusable(db)) {
            fatal(getString(R.string.fatal_guard_truncated, db.essentials.size))
            return
        }

        backup = Backup(this)
        buildFilterChips()

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        scan()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
    }

    private fun bindViews() {
        deviceName = findViewById(R.id.deviceName)
        deviceSub = findViewById(R.id.deviceSub)
        brandNotes = findViewById(R.id.brandNotes)
        shizukuCard = findViewById(R.id.shizukuCard)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        shizukuAction = findViewById(R.id.shizukuAction)
        summary = findViewById(R.id.summary)
        progress = findViewById(R.id.progress)
        listView = findViewById(R.id.list)
        filters = findViewById(R.id.filters)
        searchInput = findViewById(R.id.searchInput)
        actionBar = findViewById(R.id.actionBar)
        selectedCount = findViewById(R.id.selectedCount)
        runButton = findViewById(R.id.runButton)

        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        // The brand note is long (the OPPO one is several hundred
        // characters); show one line and let a tap expand it in place.
        brandNotes.setOnClickListener {
            brandNotes.maxLines = if (brandNotes.maxLines == 1) Int.MAX_VALUE else 1
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<MaterialButton>(R.id.clearSelection).setOnClickListener {
            all.forEach { it.selected = false }
            applyFilter()
        }
        runButton.setOnClickListener { confirmAndRun() }
    }

    private fun fatal(message: String) {
        shizukuStatus.text = message
        shizukuAction.isEnabled = false
        deviceName.text = getString(R.string.toolbar_title)
    }

    private fun buildFilterChips() {
        // The selected chip has to be unmistakable: at default Material tinting
        // on this dark palette, checked and unchecked were nearly identical.
        val accent = androidx.core.content.ContextCompat.getColor(this, R.color.cym_accent)
        val onAccent = androidx.core.content.ContextCompat.getColor(this, R.color.cym_on_accent)
        val muted = androidx.core.content.ContextCompat.getColor(this, R.color.cym_muted_foreground)
        val border = androidx.core.content.ContextCompat.getColor(this, R.color.cym_border)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())

        for (f in Filter.values()) {
            val chip = Chip(this).apply {
                text = f.label(this@MainActivity)
                isCheckable = true
                isChecked = f == filter
                chipBackgroundColor = android.content.res.ColorStateList(
                    states, intArrayOf(accent, android.graphics.Color.TRANSPARENT)
                )
                setTextColor(
                    android.content.res.ColorStateList(states, intArrayOf(onAccent, muted))
                )
                chipStrokeWidth = resources.displayMetrics.density * 1f
                chipStrokeColor = android.content.res.ColorStateList(
                    states, intArrayOf(accent, border)
                )
                isCheckedIconVisible = false
                setOnClickListener {
                    filter = f
                    applyFilter()
                }
            }
            filters.addView(chip)
        }
    }

    /**
     * Reflect a filter change made in code - importing a preset switches to
     * "Everything" so the selection is visible - in the chip row, which
     * otherwise keeps showing the old one as checked.
     */
    private fun syncFilterChips() {
        val values = Filter.values()
        for (i in 0 until filters.childCount) {
            val chip = filters.getChildAt(i) as? Chip ?: continue
            if (i < values.size) chip.isChecked = values[i] == filter
        }
    }

    // ----------------------------------------------------------------- scan --

    private fun liveState(): Guard.LiveState {
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return Guard.LiveState(home, ime)
    }

    private fun scan() {
        val installed = packageManager.getInstalledApplications(0)
        val names = installed.map { it.packageName }
        brand = Brand.detect(this, names)

        val live = liveState()
        all.clear()
        var recognised = 0
        var blocked = 0
        var off = 0
        var unrated = 0
        var regular = 0

        for (app in installed) {
            // Not a system app: no system copy to fall back on, so never offered.
            if (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                regular++
                continue
            }
            val id = app.packageName
            val entry = db.packages[id]
            if (entry == null) {
                // Not in the database, but the never-touch list still covers it.
                // Counting these as "unknown" made the guard look far less
                // active than it is. Same fix as the one in tools/scan.py.
                if (db.essentials.contains(id)) {
                    blocked++
                    continue
                }
                // Previously this just `continue`d, so an installed package the
                // database did not know never appeared anywhere in the app. On
                // an oplus phone that is a large slice of what is installed,
                // and it read as the app failing to detect anything. Show it,
                // marked unrated, and never offer it: an unrated package is
                // evidence for a human, not a verdict.
                //
                // Run the guard on it even so. A good share of these are RRO
                // overlays, framework resources and GPU drivers, which the
                // protected-name pattern already refuses. Calling those
                // "unrated" invited the user to report build infrastructure
                // for rating, and left the Unrated list a worse worklist than
                // it should be. Protected is protected; only what the guard
                // would actually allow is genuinely waiting on a rating.
                // These are rows now, so they belong in the "already off"
                // count too. Leaving them out understated it.
                if (!app.enabled) off++
                val unknownReason = Guard.blockReason(this, id, db, live)
                val syntheticEntry = Db.Pkg(
                    id = id,
                    brand = "",
                    category = "unknown",
                    safety = "Unrated",
                    name = id,
                    description = "",
                    ifRemoved = "",
                )
                if (unknownReason != null) {
                    blocked++
                    all.add(
                        Row(
                            id = id,
                            entry = syntheticEntry,
                            priority = Priority.LOW,
                            blockedBecause = unknownReason,
                            alreadyOff = !app.enabled,
                            unrated = false,
                        )
                    )
                    continue
                }
                unrated++
                all.add(
                    Row(
                        id = id,
                        entry = syntheticEntry,
                        priority = Priority.LOW,
                        blockedBecause = getString(R.string.row_not_in_database),
                        alreadyOff = !app.enabled,
                        unrated = true,
                    )
                )
                continue
            }
            recognised++
            val reason = Guard.blockReason(this, id, db, live)
            if (reason != null) blocked++
            if (!app.enabled) off++
            all.add(Row(id, entry, Priority.of(entry), reason, !app.enabled))
        }

        // Highest impact first, then most cautious last within each band.
        all.sortWith(compareBy({ it.priority.ordinal }, { it.entry.rank }, { it.entry.name }))

        deviceName.text = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } +
            " " + Build.MODEL
        deviceSub.text = buildString {
            append(brand.displayName)
            if (brand.osName.isNotBlank()) append(" · ").append(brand.osName)
            append(" · Android ").append(Build.VERSION.RELEASE)
        }
        if (brand.notes.isNotBlank()) {
            brandNotes.text = brand.notes
            brandNotes.visibility = View.VISIBLE
        } else {
            brandNotes.visibility = View.GONE
        }

        summary.text = getString(
            R.string.summary, installed.size, recognised, blocked, unrated, off, regular
        )

        applyFilter()
    }

    private fun applyFilter() {
        shown.clear()
        for (row in all) {
            val passesFilter = when (filter) {
                Filter.SUGGESTED -> row.actionable && row.priority != Priority.LOW &&
                    row.entry.safety == "Recommended"
                Filter.HIGH_IMPACT -> row.actionable && row.priority == Priority.HIGH
                Filter.ALL -> row.actionable
                // Unrated rows are non-actionable too, so keep them out of
                // "Protected" or they would bury the genuinely protected ones.
                Filter.KEPT -> !row.actionable && !row.unrated
                Filter.UNRATED -> row.unrated
            }
            if (!passesFilter) continue
            if (query.isNotEmpty() && !row.searchKey.contains(query)) continue
            shown.add(row)
        }
        adapter.notifyDataSetChanged()
        updateActionBar()
    }

    private fun updateActionBar() {
        val n = all.count { it.selected }
        if (n == 0) {
            actionBar.visibility = View.GONE
        } else {
            actionBar.visibility = View.VISIBLE
            selectedCount.text = getString(R.string.selected_count, n)
        }
    }

    // ---------------------------------------------------------------- state --

    private fun shizukuManagerInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun refresh() {
        ShizukuOps.setManagerInstalled(shizukuManagerInstalled())
        when (ShizukuOps.state()) {
            ShizukuOps.State.NOT_INSTALLED -> showShizuku(
                getString(R.string.shizuku_not_installed),
                getString(R.string.recheck)
            ) { refresh() }

            ShizukuOps.State.NOT_RUNNING -> showShizuku(
                getString(R.string.shizuku_not_running),
                getString(R.string.open_shizuku)
            ) {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.let { startActivity(it) } ?: refresh()
            }

            ShizukuOps.State.NEEDS_PERMISSION -> showShizuku(
                getString(R.string.shizuku_needs_permission),
                getString(R.string.grant_access)
            ) { ShizukuOps.requestPermission() }

            ShizukuOps.State.READY -> {
                shizukuCard.visibility = View.GONE
                runButton.isEnabled = true
            }
        }
    }

    private fun showShizuku(text: String, button: String, action: () -> Unit) {
        shizukuCard.visibility = View.VISIBLE
        shizukuStatus.text = text
        shizukuAction.text = button
        shizukuAction.setOnClickListener { action() }
        runButton.isEnabled = false
    }

    // ------------------------------------------------------------- the run --

    private fun confirmAndRun() {
        if (ShizukuOps.state() != ShizukuOps.State.READY) {
            Toast.makeText(this, R.string.shizuku_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val chosen = all.filter { it.selected && it.actionable }
        if (chosen.isEmpty()) return

        val preview = chosen.take(10).joinToString("\n") { "• " + it.entry.name }
        val more = if (chosen.size > 10) {
            getString(R.string.confirm_disable_more, chosen.size - 10)
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle(
                resources.getQuantityString(
                    R.plurals.confirm_disable_title, chosen.size, chosen.size
                )
            )
            .setMessage(preview + more + "\n\n" + getString(R.string.confirm_disable_message))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disable) { _, _ -> run(chosen) }
            .show()
    }

    private fun run(chosen: List<Row>) {
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.max = chosen.size
        progress.progress = 0

        thread {
            // AGENTS.md rule 4: the record and the baseline exist before the
            // first change, not after the last one.
            backup.captureSnapshot(brand)
            backup.startSession()

            var done = 0
            var failed = 0
            var consecutive = 0
            val problems = mutableListOf<String>()

            for ((i, row) in chosen.withIndex()) {
                // The guard runs again here: the default launcher or keyboard
                // may have changed since the scan.
                val reason = Guard.blockReason(this, row.id, db, liveState())
                if (reason != null) {
                    problems.add(getString(R.string.run_refused, row.entry.name, reason))
                    failed++
                    continue
                }

                val reply = ShizukuOps.disable(row.id)
                if (reply.ok) {
                    backup.recordDisabled(row.id)
                    done++
                    consecutive = 0
                } else {
                    failed++
                    consecutive++
                    problems.add(
                        getString(
                            R.string.run_failed,
                            row.entry.name,
                            ShizukuOps.explainFailure(this, reply.output),
                        )
                    )
                    if (consecutive >= maxConsecutiveFailures) {
                        problems.add(
                            getString(R.string.run_stopped_consecutive, consecutive)
                        )
                        break
                    }
                }

                val p = i + 1
                main.post { progress.progress = p }

                if (p % batchSize == 0 && !ShizukuOps.packageServiceAlive()) {
                    problems.add(getString(R.string.run_package_service_dead))
                    break
                }
            }

            val d = done
            val f = failed
            main.post {
                progress.visibility = View.GONE
                runButton.isEnabled = true
                all.forEach { it.selected = false }
                scan()
                val detail = if (problems.isEmpty()) "" else "\n\n" + problems.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle(if (f == 0) R.string.run_done_title else R.string.run_problems_title)
                    .setMessage(getString(R.string.run_result, d, f) + detail)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    // --------------------------------------------------------------- menu ----

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, R.string.menu_restore_all)
        menu.add(0, 2, 0, R.string.menu_export_backup)
        menu.add(0, 3, 0, R.string.menu_export_preset)
        menu.add(0, 4, 0, R.string.menu_import_preset)
        menu.add(0, 5, 0, R.string.appearance)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            1 -> confirmRestoreAll()
            2 -> exportBackup()
            3 -> exportPreset()
            4 -> chooseImportSource()
            5 -> chooseTheme()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun confirmRestoreAll() {
        val packages = backup.stillOff()
        if (packages.isEmpty()) {
            Toast.makeText(this, R.string.restore_nothing_off, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                resources.getQuantityString(
                    R.plurals.restore_title, packages.size, packages.size
                )
            )
            .setMessage(R.string.restore_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ ->
                thread {
                    var ok = 0
                    for (p in packages) {
                        if (ShizukuOps.enable(p).ok) ok++ else ShizukuOps.installExisting(p)
                    }
                    val n = ok
                    main.post {
                        backup.clearRecord()
                        scan()
                        Toast.makeText(
                            this,
                            resources.getQuantityString(R.plurals.restored_toast, n, n),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun chooseTheme() {
        val modes = ThemeMode.values()
        val current = ThemeMode.load(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(
                modes.map { it.label(this) }.toTypedArray(),
                modes.indexOf(current),
            ) { dialog, which ->
                val picked = modes[which]
                ThemeMode.save(this, picked)
                // Takes effect immediately: the delegate swaps the resource
                // bucket and recreates the activity for us.
                ThemeMode.apply(picked)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------- presets ---

    /**
     * The file picker for an imported preset. Registered as a property so it
     * exists before the activity is started, which is what the contract API
     * requires.
     */
    private val pickPreset = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Throwable) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.preset_read_failed, Toast.LENGTH_LONG).show()
        } else {
            handlePresetText(text)
        }
    }

    /**
     * Share what this app actually switched off, as a preset another phone or
     * the command line tools can read.
     */
    private fun exportPreset() {
        val packages = backup.stillOff()
        if (packages.isEmpty()) {
            Toast.makeText(
                this, R.string.preset_nothing_to_save,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        try {
            val preset = Preset.build(
                name = Build.MANUFACTURER + "-" + Build.MODEL,
                packages = packages,
                description = getString(
                    R.string.preset_export_description,
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
                ),
                brand = brand.key,
                mode = "disable",
            )
            val file = Preset.writeForShare(this, preset)
            val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.menu_export_preset)))
        } catch (t: Throwable) {
            Toast.makeText(
                this, getString(R.string.preset_export_failed, t.message), Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun chooseImportSource() {
        AlertDialog.Builder(this)
            .setTitle(R.string.preset_import_title)
            .setItems(
                arrayOf(
                    getString(R.string.preset_import_file),
                    getString(R.string.preset_import_clipboard),
                )
            ) { _, which ->
                if (which == 0) {
                    // Some providers mislabel .json, so accept anything and let
                    // the parser be the judge.
                    pickPreset.launch(arrayOf("*/*"))
                } else {
                    val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(this).toString()
                    } else {
                        ""
                    }
                    if (text.isBlank()) {
                        Toast.makeText(this, R.string.preset_clipboard_empty, Toast.LENGTH_LONG).show()
                    } else {
                        handlePresetText(text)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Parse, then explain, then tick boxes. Importing a preset never changes
     * anything on the phone: it selects what the guard allows, and the normal
     * guarded flow still has to be run deliberately afterwards.
     */
    private fun handlePresetText(text: String) {
        when (val outcome = Preset.parse(this, text)) {
            is Preset.Outcome.Refused -> AlertDialog.Builder(this)
                .setTitle(R.string.preset_refused_title)
                .setMessage(
                    outcome.why + "\n\n" + getString(R.string.preset_refused_suffix)
                )
                .setPositiveButton(R.string.close, null)
                .show()

            is Preset.Outcome.Ok -> showPresetPlan(outcome.preset)
        }
    }

    private fun showPresetPlan(preset: Preset.Data) {
        val installedApps = packageManager.getInstalledApplications(0)
        val systemApps = installedApps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        val installed = systemApps.map { it.packageName }.toSet()
        val disabled = systemApps.filter { !it.enabled }.map { it.packageName }.toSet()
        val regular = installedApps.map { it.packageName }.toSet() - installed

        val res = Preset.resolve(this, preset, db, liveState(), installed, disabled)
        val notInstalled = res.notInstalled.filter { it !in regular }
        val leftAlone = res.notInstalled.filter { it in regular }

        val sb = StringBuilder()
        if (preset.description.isNotBlank()) sb.append(preset.description).append("\n\n")
        sb.append(
            resources.getQuantityString(
                R.plurals.preset_plan_packages, preset.packages.size, preset.packages.size
            )
        ).append("\n")

        if (res.ready.isNotEmpty()) {
            sb.append("\n").append(getString(R.string.preset_plan_ready, res.ready.size))
                .append("\n")
            for (p in res.ready.take(20)) {
                sb.append("  ").append(p.name.ifBlank { p.id })
                    .append(" [").append(safetyLabel(p.safety)).append("]\n")
            }
            if (res.ready.size > 20) {
                sb.append(getString(R.string.preset_plan_more, res.ready.size - 20))
                    .append("\n")
            }
        }
        if (res.refused.isNotEmpty()) {
            sb.append("\n").append(getString(R.string.preset_plan_refused, res.refused.size))
                .append("\n")
            for ((id, why) in res.refused.take(10)) {
                sb.append(getString(R.string.preset_plan_refused_item, id, why)).append("\n")
            }
            if (res.refused.size > 10) {
                sb.append(getString(R.string.preset_plan_more, res.refused.size - 10))
                    .append("\n")
            }
        }
        if (res.readyUnknown.isNotEmpty()) {
            sb.append("\n")
                .append(getString(R.string.preset_plan_unknown, res.readyUnknown.size))
                .append("\n")
            for (id in res.readyUnknown.take(10)) sb.append("  ").append(id).append("\n")
            if (res.readyUnknown.size > 10) {
                sb.append(getString(R.string.preset_plan_more, res.readyUnknown.size - 10))
                    .append("\n")
            }
        }
        if (res.alreadyOff.isNotEmpty()) {
            sb.append("\n")
                .append(getString(R.string.preset_plan_already_off, res.alreadyOff.size))
                .append("\n")
        }
        if (leftAlone.isNotEmpty()) {
            sb.append("\n").append(getString(R.string.preset_plan_regular, leftAlone.size))
                .append("\n")
        }
        if (notInstalled.isNotEmpty()) {
            sb.append("\n")
                .append(getString(R.string.preset_plan_not_installed, notInstalled.size))
                .append("\n")
        }

        val selectable = res.ready.map { it.id }.toSet()
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.preset_plan_title, preset.name))
            .setMessage(sb.toString())
            .setNegativeButton(R.string.cancel, null)

        if (selectable.isEmpty()) {
            builder.setPositiveButton(R.string.close, null)
        } else {
            builder.setPositiveButton(
                getString(R.string.preset_plan_select, selectable.size)
            ) { _, _ ->
                var ticked = 0
                for (row in all) {
                    if (row.actionable && selectable.contains(row.id)) {
                        row.selected = true
                        ticked++
                    }
                }
                // Show them: the default filter would hide most of a preset.
                filter = Filter.ALL
                syncFilterChips()
                applyFilter()
                Toast.makeText(
                    this,
                    resources.getQuantityString(
                        R.plurals.preset_plan_selected, ticked, ticked
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        builder.show()
    }

    private fun exportBackup() {
        try {
            backup.captureSnapshot(brand)
            val file = backup.exportText()
            val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.menu_export_backup)))
        } catch (t: Throwable) {
            Toast.makeText(
                this, getString(R.string.preset_export_failed, t.message), Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Display label for a database safety rating. The database values stay
     * English — they are logic keys, shared with the shell tools — so the
     * badge and the preset list translate them here.
     */
    private fun safetyLabel(safety: String): String = when (safety) {
        "Recommended" -> getString(R.string.safety_recommended)
        "Advanced" -> getString(R.string.safety_advanced)
        "Expert" -> getString(R.string.safety_expert)
        "Unsafe" -> getString(R.string.safety_unsafe)
        else -> getString(R.string.safety_unrated)
    }

    // --------------------------------------------------------------- adapter --

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        // Resolved once per adapter instead of once per badge per bind. Each of
        // these was a resources or ContextCompat lookup inside the scroll path.
        //
        // density MUST be lazy: the Adapter is created as a MainActivity field
        // initializer, which runs during Activity instantiation, before
        // attachBaseContext() — the base Context is still null there, so
        // touching resources eagerly throws NullPointerException and the app
        // crashes on launch. First use is in newBadge(), long after attach.
        private val density: Float by lazy { resources.displayMetrics.density }
        private val transparent = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.TRANSPARENT
        )
        private val tints = HashMap<Int, android.content.res.ColorStateList>()

        private fun tint(res: Int): android.content.res.ColorStateList = tints.getOrPut(res) {
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this@MainActivity, res)
            )
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.card)
            val check: MaterialCheckBox = view.findViewById(R.id.check)
            val name: TextView = view.findViewById(R.id.name)
            val pkg: TextView = view.findViewById(R.id.pkg)
            val impact: TextView = view.findViewById(R.id.impact)
            val badges: ChipGroup = view.findViewById(R.id.badges)

            // A row carries at most two badges: the safety rating, and the
            // impact band when the row is actionable. They are built once here
            // and re-labelled on bind. Building them inside onBindViewHolder
            // meant constructing Material Chip views - attribute resolution,
            // colour state lists, density maths - for every row that scrolled
            // into view, which is the one thing a bind must not do.
            val safetyChip: Chip = newBadge().also { badges.addView(it) }
            val priorityChip: Chip = newBadge().also { badges.addView(it) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_package, parent, false))

        override fun getItemCount(): Int = shown.size

        /**
         * Badges carry colour as reinforcement, never as the only signal: the
         * label still says "High impact" or "Expert" in words, which is the
         * skill's colour-not-only accessibility rule.
         */
        private fun newBadge(): Chip = Chip(this@MainActivity).apply {
            isClickable = false
            isCheckable = false
            // Inline labels, not tap targets: without this they render
            // at full 48dp touch-target height and dwarf the row.
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = density * 26f
            chipStartPadding = density * 2f
            chipEndPadding = density * 2f
            chipStrokeWidth = density * 1f
            textSize = 11f
            chipBackgroundColor = transparent
        }

        /** Re-label an existing badge. No allocation beyond the text itself. */
        private fun setBadge(chip: Chip, label: String, colourRes: Int) {
            val colours = tint(colourRes)
            chip.text = label
            chip.chipStrokeColor = colours
            chip.setTextColor(colours)
            chip.visibility = View.VISIBLE
        }

        private fun safetyTint(safety: String): Int = when (safety) {
            "Recommended" -> R.color.cym_accent
            "Advanced" -> R.color.cym_foreground
            else -> R.color.cym_warning
        }

        private fun priorityTint(p: Priority): Int = when (p) {
            Priority.HIGH -> R.color.cym_accent
            Priority.MEDIUM -> R.color.cym_muted_foreground
            Priority.LOW -> R.color.cym_muted_foreground
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = shown[position]
            holder.name.text = row.entry.name
            holder.pkg.text = row.id

            setBadge(holder.safetyChip, safetyLabel(row.entry.safety), safetyTint(row.entry.safety))
            if (row.actionable) {
                setBadge(
                    holder.priorityChip,
                    row.priority.label(this@MainActivity),
                    priorityTint(row.priority),
                )
            } else {
                holder.priorityChip.visibility = View.GONE
            }

            when {
                row.alreadyOff -> {
                    holder.impact.text = this@MainActivity.getString(R.string.row_already_off)
                    holder.check.isChecked = false
                    holder.card.isEnabled = false
                    holder.card.alpha = 0.5f
                    holder.card.setOnClickListener(null)
                }
                row.unrated -> {
                    // Honest wording: this one is not protected, it is simply
                    // not rated. Saying "Protected" here would be a lie.
                    holder.impact.text = this@MainActivity.getString(R.string.row_unrated)
                    holder.check.isChecked = false
                    holder.card.isEnabled = false
                    holder.card.alpha = 0.5f
                    holder.card.setOnClickListener(null)
                }
                !row.actionable -> {
                    // Shown, never offered. The user should know it was seen.
                    holder.impact.text = this@MainActivity.getString(
                        R.string.row_protected, row.blockedBecause
                    )
                    holder.check.isChecked = false
                    holder.card.isEnabled = false
                    holder.card.alpha = 0.5f
                    holder.card.setOnClickListener(null)
                }
                else -> {
                    holder.impact.text = this@MainActivity.getString(
                        R.string.row_if_removed, row.entry.ifRemoved
                    )
                    holder.check.isChecked = row.selected
                    holder.card.isEnabled = true
                    holder.card.alpha = 1f
                    holder.card.isChecked = row.selected
                    holder.card.setOnClickListener {
                        row.selected = !row.selected
                        holder.check.isChecked = row.selected
                        holder.card.isChecked = row.selected
                        updateActionBar()
                    }
                }
            }
        }
    }
}
