package com.cleanyourmobile

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * Which manufacturer is this, and what should the user know about it?
 *
 * Ported from Database.detect_brand() in tools/cym.py: score every profile by
 * how many installed packages start with one of its prefixes, then add a large
 * bonus to whichever profile's name or alias matches Build.BRAND. The alias
 * step matters because ro.product.brand reports "Redmi" and "POCO", not
 * "Xiaomi", and "iQOO", not "vivo".
 */
class Brand private constructor(
    val key: String,
    val displayName: String,
    val osName: String,
    val notes: String,
    val commonTargets: List<String>,
) {
    companion object {
        private const val NAME_MATCH_BONUS = 25

        /** No brand profile matched: universal entries still apply. */
        fun unknown(context: Context): Brand = Brand(
            key = "",
            displayName = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            osName = "Android " + Build.VERSION.RELEASE,
            notes = context.getString(R.string.brand_unknown_notes),
            commonTargets = emptyList(),
        )

        fun detect(context: Context, installed: Collection<String>): Brand {
            val root = try {
                JSONObject(
                    context.assets.open("brand_profiles.json")
                        .bufferedReader().use { it.readText() }
                )
            } catch (t: Throwable) {
                return unknown(context)
            }
            val brands = root.optJSONObject("brands") ?: return unknown(context)

            val deviceBrand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
            var bestKey = ""
            var bestScore = 0
            var best: JSONObject? = null

            for (key in brands.keys()) {
                val profile = brands.optJSONObject(key) ?: continue

                var score = 0
                val prefixes = profile.optJSONArray("detection_prefixes")
                if (prefixes != null) {
                    val list = (0 until prefixes.length()).map { prefixes.getString(it) }
                    score += installed.count { pkg -> list.any { pkg.startsWith(it) } }
                }

                // The name bonus, same as the Python version.
                val aliases = mutableListOf(key)
                profile.optJSONArray("aliases")?.let { arr ->
                    for (i in 0 until arr.length()) aliases.add(arr.getString(i))
                }
                if (aliases.any { deviceBrand.contains(it.lowercase()) }) {
                    score += NAME_MATCH_BONUS
                }

                if (score > bestScore) {
                    bestScore = score
                    bestKey = key
                    best = profile
                }
            }

            val profile = best
            if (profile == null || bestScore == 0) return unknown(context)

            val targets = mutableListOf<String>()
            profile.optJSONArray("common_safe_removals")?.let { arr ->
                for (i in 0 until arr.length()) targets.add(arr.getString(i))
            }

            return Brand(
                key = bestKey,
                displayName = profile.optString("display_name", bestKey),
                osName = profile.optString("os_name", ""),
                notes = profile.optString("special_notes", ""),
                commonTargets = targets,
            )
        }
    }
}
