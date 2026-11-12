package com.cleanyourmobile

import android.content.Context
import org.json.JSONObject

/**
 * The debloat database, read from the APK's assets. These are byte-for-byte the
 * same files the shell and Python tools read: database/debloat_db.json and
 * database/essential_packages.json, copied in at build time.
 */
class Db private constructor(
    val packages: Map<String, Pkg>,
    val essentials: Set<String>,
    val schemaVersion: String,
) {
    data class Pkg(
        val id: String,
        val brand: String,
        val category: String,
        val safety: String,
        val name: String,
        val description: String,
        val ifRemoved: String,
    ) {
        /** Higher is more cautious. */
        val rank: Int get() = SAFETY_ORDER.indexOf(safety).let { if (it < 0) 2 else it }
    }

    companion object {
        val SAFETY_ORDER = listOf("Recommended", "Advanced", "Expert", "Unsafe")

        /** Schema major this code understands. */
        private const val SUPPORTED_SCHEMA_MAJOR = 1

        fun load(context: Context): Db {
            val dbJson = JSONObject(readAsset(context, "debloat_db.json"))
            val essJson = JSONObject(readAsset(context, "essential_packages.json"))

            val meta = dbJson.optJSONObject("_meta")
            val schema = meta?.optString("schema_version") ?: ""
            val major = schema.substringBefore('.').toIntOrNull()
            require(major == SUPPORTED_SCHEMA_MAJOR) {
                context.getString(R.string.db_schema_mismatch, schema, SUPPORTED_SCHEMA_MAJOR)
            }

            // A package shared across brands appears once per brand. Keep one
            // entry per id and, if the ratings ever disagree, the most cautious.
            val packages = HashMap<String, Pkg>()
            val arr = dbJson.getJSONArray("packages")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Pkg(
                    id = o.getString("id"),
                    brand = o.optString("brand", "universal"),
                    category = o.optString("category", "bloatware"),
                    safety = o.optString("safety", "Expert"),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    ifRemoved = o.optString("if_removed"),
                )
                val existing = packages[p.id]
                if (existing == null || p.rank > existing.rank) packages[p.id] = p
            }

            val essentials = HashSet<String>()
            for (key in essJson.keys()) {
                if (key == "_meta") continue
                val list = essJson.optJSONArray(key) ?: continue
                for (i in 0 until list.length()) essentials.add(list.getString(i))
            }
            // Same stance as every other tool: no guard, no run.
            require(essentials.isNotEmpty()) {
                context.getString(R.string.db_essentials_empty)
            }

            return Db(packages, essentials, schema)
        }

        private fun readAsset(context: Context, name: String): String =
            context.assets.open(name).bufferedReader().use { it.readText() }
    }
}
