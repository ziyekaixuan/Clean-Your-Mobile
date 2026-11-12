package com.cleanyourmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Portable removal profiles - the same `cym-preset` JSON that tools/preset.py
 * reads and writes, so a profile made on a phone applies on a computer and the
 * other way round.
 *
 * A preset holds package names and nothing else. Ratings, descriptions and the
 * if_removed line are resolved from the database every time one is opened, so a
 * profile written a year ago picks up today's ratings rather than carrying a
 * stale copy of them.
 *
 * SECURITY. An imported preset is a file from someone else, and its package
 * names end up in a privileged `pm` call. Every name is checked against
 * PACKAGE_PATTERN_SOURCE below, and a file with one bad name is rejected whole
 * rather than applied in part - a half-applied preset is the dangerous kind.
 * Importing never changes anything either way: it only ticks checkboxes, and
 * the existing guarded flow still has to be run by hand afterwards.
 *
 * This is the SECOND implementation of the preset format:
 *
 *   - tools/preset.py   PKG_RE, MAX_PKG_LEN, MAX_PACKAGES  (source of truth)
 *   - this file
 *
 * `python tools/sync_guard.py` verifies the two agree. Change one, change both.
 */
object Preset {

    const val SCHEMA = "cym-preset"
    const val SCHEMA_VERSION = "1.0.0"

    // KEPT IN STEP WITH tools/preset.py - verified by tools/sync_guard.py
    const val PACKAGE_PATTERN_SOURCE = """[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*"""
    const val MAX_PKG_LEN = 255
    const val MAX_PACKAGES = 2000

    private val packageRe = Regex(PACKAGE_PATTERN_SOURCE)

    data class Data(
        val name: String,
        val description: String,
        val brand: String,
        val mode: String,
        val created: String,
        val packages: List<String>,
    )

    /** Parsing either produces a preset or a reason it was refused. */
    sealed class Outcome {
        data class Ok(val preset: Data) : Outcome()
        data class Refused(val why: String) : Outcome()
    }

    // ------------------------------------------------------------- reading ---

    fun parse(context: Context, text: String): Outcome {
        val root: JSONObject = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Outcome.Refused(
                context.getString(
                    R.string.preset_bad_json,
                    e.message ?: context.getString(R.string.preset_unparseable),
                )
            )
        }

        val schema = root.optString("schema", "")
        if (schema.isNotEmpty() && schema != SCHEMA) {
            return Outcome.Refused(context.getString(R.string.preset_wrong_schema, schema))
        }

        val arr: JSONArray = root.optJSONArray("packages")
            ?: return Outcome.Refused(context.getString(R.string.preset_no_packages))

        if (arr.length() > MAX_PACKAGES) {
            return Outcome.Refused(
                context.getString(R.string.preset_too_many, arr.length(), MAX_PACKAGES)
            )
        }

        val packages = ArrayList<String>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val raw = arr.opt(i)
            if (raw !is String) {
                return Outcome.Refused(
                    context.getString(R.string.preset_bad_entry, i + 1)
                )
            }
            val id = raw.trim()
            if (id.isEmpty() || id.startsWith("#")) continue
            if (id.length > MAX_PKG_LEN) {
                return Outcome.Refused(
                    context.getString(R.string.preset_entry_too_long, i + 1, MAX_PKG_LEN)
                )
            }
            if (!packageRe.matches(id)) {
                // This is the branch that stops a shared file smuggling a shell
                // metacharacter into a privileged pm call.
                return Outcome.Refused(
                    context.getString(R.string.preset_bad_name, id.take(60))
                )
            }
            if (seen.add(id)) packages.add(id)
        }

        if (packages.isEmpty()) return Outcome.Refused(context.getString(R.string.preset_empty))

        val mode = root.optString("mode", "remove").let {
            if (it == "disable" || it == "remove") it else "remove"
        }
        return Outcome.Ok(
            Data(
                name = root.optString("name", context.getString(R.string.preset_default_name)),
                description = root.optString("description", ""),
                brand = root.optString("brand", ""),
                mode = mode,
                created = root.optString("created", ""),
                packages = packages,
            )
        )
    }

    // ------------------------------------------------------------- writing ---

    fun build(name: String, packages: List<String>, description: String, brand: String,
              mode: String = "disable"): Data = Data(
        name = slug(name),
        description = description,
        brand = brand,
        mode = mode,
        created = iso(Date()),
        packages = packages,
    )

    fun toJson(d: Data): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("schema_version", SCHEMA_VERSION)
        put("name", d.name)
        put("description", d.description)
        put("brand", d.brand)
        put("mode", d.mode)
        put("created", d.created)
        put("packages", JSONArray().also { a -> d.packages.forEach { a.put(it) } })
    }.toString(2)

    /**
     * Write a preset into the app's own cache so the share sheet can send it
     * without needing any storage permission.
     */
    fun writeForShare(context: Context, d: Data): File {
        val out = File(context.cacheDir, "cleanyourmobile-preset-" + d.name + ".json")
        out.writeText(toJson(d))
        return out
    }

    // ------------------------------------------------------------ resolving --

    /**
     * What a preset means on THIS phone. Nothing is acted on here; this only
     * sorts the names so the user can be shown the cost before they agree.
     */
    data class Resolved(
        val ready: List<Db.Pkg>,
        val readyUnknown: List<String>,
        val refused: List<Pair<String, String>>,
        val notInstalled: List<String>,
        val alreadyOff: List<String>,
    )

    fun resolve(
        context: Context,
        preset: Data,
        db: Db,
        live: Guard.LiveState,
        installed: Set<String>,
        disabled: Set<String>,
    ): Resolved {
        val ready = ArrayList<Db.Pkg>()
        val readyUnknown = ArrayList<String>()
        val refused = ArrayList<Pair<String, String>>()
        val notInstalled = ArrayList<String>()
        val alreadyOff = ArrayList<String>()

        for (id in preset.packages) {
            // The guard first, always, and with no way past it.
            val blocked = Guard.blockReason(context, id, db, live)
            if (blocked != null) {
                refused.add(id to blocked)
                continue
            }
            if (disabled.contains(id)) {
                alreadyOff.add(id)
                continue
            }
            if (!installed.contains(id)) {
                notInstalled.add(id)
                continue
            }
            val entry = db.packages[id]
            if (entry == null) readyUnknown.add(id) else ready.add(entry)
        }
        ready.sortWith(compareBy({ it.rank }, { it.id }))
        return Resolved(ready, readyUnknown, refused, notInstalled, alreadyOff)
    }

    // -------------------------------------------------------------- helpers --

    fun slug(text: String): String {
        val cleaned = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (cleaned.isEmpty()) "preset" else cleaned
    }

    private fun iso(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(d)
}
