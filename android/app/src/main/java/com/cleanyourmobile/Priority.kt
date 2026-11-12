package com.cleanyourmobile

import android.content.Context
import androidx.annotation.StringRes

/**
 * How much does removing this actually improve the phone?
 *
 * The safety rating says how risky a removal is. It says nothing about how
 * worthwhile it is, and those are different questions: a preinstalled compass
 * and a lock-screen ad carousel are both "Recommended", but only one of them is
 * doing you harm. This orders the list so the things people actually came here
 * for are at the top.
 *
 * Labels live in string resources so they localise; the enum itself holds
 * only resource ids.
 */
enum class Priority(@StringRes val labelRes: Int, @StringRes val blurbRes: Int) {
    HIGH(R.string.priority_high, R.string.priority_high_blurb),
    MEDIUM(R.string.priority_medium, R.string.priority_medium_blurb),
    LOW(R.string.priority_low, R.string.priority_low_blurb),
    ;

    /** Display label in the current locale. */
    fun label(context: Context): String = context.getString(labelRes)

    /** Display blurb in the current locale. */
    fun blurb(context: Context): String = context.getString(blurbRes)

    companion object {
        fun of(entry: Db.Pkg): Priority = when (entry.category) {
            // Actively working against you: serving ads, or sending data out.
            "ads", "tracker", "telemetry" -> HIGH
            // Dead weight: shipped with the phone, never asked for.
            "bloatware", "partner", "carrier" -> MEDIUM
            // You already have something better, but it is only clutter.
            "duplicate", "google" -> MEDIUM
            // A real feature. Removing it is a preference, not a fix.
            "oem_feature" -> LOW
            else -> LOW
        }
    }
}

/** The filter chips across the top of the list. */
enum class Filter(@StringRes val labelRes: Int) {
    SUGGESTED(R.string.filter_suggested),
    HIGH_IMPACT(R.string.filter_high_impact),
    ALL(R.string.filter_all),
    KEPT(R.string.filter_kept),
    // Installed, but the database has no entry for it. These used to be left
    // out of the list altogether, which on a ColorOS/realme UI/OxygenOS phone
    // silently hid a large slice of what is actually installed and read as
    // "the app does not detect my apps". They are shown, never offered.
    UNRATED(R.string.filter_unrated),
    ;

    /** Display label in the current locale. */
    fun label(context: Context): String = context.getString(labelRes)
}
