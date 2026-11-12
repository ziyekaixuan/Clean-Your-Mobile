package com.cleanyourmobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A snapshot of the phone before anything changes, and the record of what was
 * changed.
 *
 * The shell tools write a whole session directory for this. The app writes the
 * same two things in miniature:
 *
 *   snapshot_before.json  what the phone looked like - every package, which
 *                         ones were already disabled, and which build it was
 *   restore_record.txt    one undo line per package, appended the moment that
 *                         package comes off
 *
 * AGENTS.md rule 4: the record exists before the first change, not after the
 * last one, so a run that dies halfway still leaves something that works.
 */
class Backup(private val context: Context) {

    private val dir: File get() = context.filesDir
    val snapshotFile: File get() = File(dir, "snapshot_before.json")
    val recordFile: File get() = File(dir, "restore_record.txt")

    /** True once a baseline exists, so we never overwrite the original one. */
    fun hasSnapshot(): Boolean = snapshotFile.exists()

    /**
     * Record the phone as it is now. Called before the first change of a
     * session and never again, because the useful baseline is the untouched
     * one, not the state after the last run.
     */
    fun captureSnapshot(brand: Brand) {
        if (hasSnapshot()) return

        val pm = context.packageManager
        val installed = pm.getInstalledApplications(0)

        val all = JSONArray()
        val disabled = JSONArray()
        for (app in installed) {
            all.put(app.packageName)
            if (!app.enabled) disabled.put(app.packageName)
        }

        val payload = JSONObject().apply {
            put("taken", iso(Date()))
            put("device", JSONObject().apply {
                put("brand", Build.BRAND)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("build", Build.DISPLAY)
                put("security_patch", Build.VERSION.SECURITY_PATCH)
            })
            put("brand_profile", brand.key)
            put("counts", JSONObject().apply {
                put("installed", all.length())
                put("disabled", disabled.length())
            })
            put("packages", all)
            put("disabled", disabled)
        }
        snapshotFile.writeText(payload.toString(2))
    }

    fun startSession() {
        recordFile.appendText(
            context.getString(R.string.backup_session, iso(Date())) + System.lineSeparator()
        )
    }

    /** One undo line, written the moment the package comes off. */
    fun recordDisabled(pkg: String) {
        recordFile.appendText("pm enable --user 0 " + pkg + System.lineSeparator())
    }

    fun recordUninstalled(pkg: String) {
        recordFile.appendText("cmd package install-existing " + pkg + System.lineSeparator())
    }

    /** Every package this app has switched off, newest session last. */
    fun recordedPackages(): List<String> {
        if (!recordFile.exists()) return emptyList()
        return recordFile.readLines()
            .filter { it.startsWith("pm enable") || it.startsWith("cmd package") }
            .mapNotNull { it.trim().split(" ").lastOrNull() }
            .distinct()
    }

    /**
     * Of the recorded packages, the ones that are genuinely still switched off.
     *
     * The record is append-only, so a package restored in an earlier session
     * stays in it. Reporting the raw record as "switched off" overstates what
     * is actually off - which in a backup file is the kind of wrong that makes
     * someone distrust the whole thing.
     */
    fun stillOff(): List<String> {
        val pm = context.packageManager
        return recordedPackages().filter { pkg ->
            try {
                !pm.getApplicationInfo(pkg, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                // Uninstalled for this user counts as still off.
                true
            }
        }
    }

    fun clearRecord() {
        if (recordFile.exists()) recordFile.delete()
    }

    /**
     * Everything needed to reproduce this on a computer, as one text file the
     * user can share or keep. Written to the app's own cache so it can be sent
     * with a share sheet without needing storage permission.
     */
    fun exportText(): File {
        val out = File(context.cacheDir, "cleanyourmobile-backup.txt")
        val sb = StringBuilder()
        sb.append(context.getString(R.string.backup_title)).append(nl)
        sb.append(context.getString(R.string.backup_exported, iso(Date()))).append(nl)
        sb.append(
            context.getString(
                R.string.backup_device,
                Build.BRAND + " " + Build.MODEL + " (" + Build.DEVICE + ")",
            )
        ).append(nl)
        sb.append(
            context.getString(
                R.string.backup_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT
            )
        ).append(nl)
        sb.append(context.getString(R.string.backup_build, Build.DISPLAY)).append(nl).append(nl)

        val off = stillOff()
        val restored = recordedPackages() - off.toSet()

        sb.append(context.getString(R.string.backup_off_header, off.size)).append(nl)
        if (off.isEmpty()) sb.append(context.getString(R.string.backup_none)).append(nl)
        for (p in off) sb.append("  ").append(p).append(nl)
        sb.append(nl)

        if (restored.isNotEmpty()) {
            sb.append(context.getString(R.string.backup_restored_header, restored.size))
                .append(nl)
            for (p in restored) sb.append("  ").append(p).append(nl)
            sb.append(nl)
        }

        if (off.isNotEmpty()) {
            sb.append(context.getString(R.string.backup_undo_header)).append(nl)
            for (p in off) {
                sb.append("adb shell pm enable --user 0 ").append(p).append(nl)
            }
            sb.append(nl)
            sb.append(context.getString(R.string.backup_undo_app)).append(nl)
        }

        if (snapshotFile.exists()) {
            sb.append(nl).append(context.getString(R.string.backup_snapshot)).append(nl)
            sb.append("  ").append(snapshotFile.absolutePath).append(nl)
        }

        out.writeText(sb.toString())
        return out
    }

    private val nl = System.lineSeparator()

    private fun iso(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d)
}
