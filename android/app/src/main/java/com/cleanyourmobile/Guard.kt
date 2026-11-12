package com.cleanyourmobile

import android.content.Context

/**
 * The guard. This is the FOURTH implementation of the same three checks:
 *
 *   - block_reason()            in scripts/lib.sh
 *   - Get-BlockReason           in scripts/lib.ps1
 *   - Database.block_reason()   in tools/cym.py
 *   - this file
 *
 * AGENTS.md: "Change one of the three protected-name patterns and you must
 * change all three." It is now four. PROTECTED_PATTERN below is generated from
 * scripts/lib.sh by tools/sync_guard.py - run that after editing the shell
 * copy, and never hand-edit the pattern here.
 */
object Guard {

    // GENERATED FROM scripts/lib.sh - DO NOT EDIT BY HAND
    const val PROTECTED_PATTERN_SOURCE = """(^android$|^oplus$|systemui|^com\.android\.settings|telecom|telephony|dialer|incallui|\.mms|messaging|inputmethod|latin|keyboard|^com\.android\.vending$|^com\.google\.android\.gms|^com\.google\.android\.gsf|setupwizard|provision|keystore|keychain|permissioncontroller|packageinstaller|networkstack|\.wifi|bluetooth|^com\.android\.camera|launcher|\.home$|overlay|\.rro\.|_rro$|frameworkres|gpudrivers|vulkan|\.resources$|^android\.auto_generated)"""

    private val protectedRe = Regex(PROTECTED_PATTERN_SOURCE)

    /** Live device state the database cannot know about. */
    data class LiveState(val homePackage: String?, val imePackage: String?)

    /**
     * Why this package must be kept, or null when it may be touched.
     * Order matters: the never-touch list is checked first and has no override.
     * The reason is localised: it is shown to the user as-is.
     */
    fun blockReason(
        context: Context,
        id: String,
        db: Db,
        live: LiveState,
        allowProtectedNames: Boolean = false,
    ): String? {
        // 1. The never-touch list. No override exists. Do not add one.
        if (db.essentials.contains(id)) return context.getString(R.string.guard_never_touch)

        // 2. Anything the database itself rates Unsafe.
        if (db.packages[id]?.safety == "Unsafe") return context.getString(R.string.guard_unsafe)

        // 3. A name that means "do not touch" on an unfamiliar ROM.
        if (!allowProtectedNames && protectedRe.containsMatchIn(id)) {
            return context.getString(R.string.guard_protected_name)
        }

        // 4. What the phone is relying on right this moment.
        if (id == live.homePackage) return context.getString(R.string.guard_home_launcher)
        if (id == live.imePackage) return context.getString(R.string.guard_keyboard)

        return null
    }

    /** True when the never-touch list looks unusable. Callers must refuse to run. */
    fun guardUnusable(db: Db): Boolean = db.essentials.size < 50
}
