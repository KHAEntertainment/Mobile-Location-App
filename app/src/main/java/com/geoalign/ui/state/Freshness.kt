package com.geoalign.ui.state

/** How long ago the current reading was taken, in words. Pure; [nowMillis] is injected. */
object Freshness {

    fun ageMillis(checkedAtMillis: Long?, nowMillis: Long): Long? =
        checkedAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) }

    /**
     * Negative deltas are clamped to zero rather than rendered. Device clocks move — a resync or a
     * timezone change can put `now` behind the timestamp, and "Checked -3 min ago" reads as a bug.
     */
    fun checkedLabel(checkedAtMillis: Long?, nowMillis: Long, copy: ReadinessCopy): String {
        val age = ageMillis(checkedAtMillis, nowMillis) ?: return copy.notCheckedYet
        val seconds = age / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            seconds < 60 -> copy.checkedMomentsAgo
            minutes < 60 -> copy.checkedMinutesAgo(minutes)
            hours < 24 -> copy.checkedHoursAgo(hours)
            else -> copy.checkedOverADayAgo
        }
    }
}
