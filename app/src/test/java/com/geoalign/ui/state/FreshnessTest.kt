package com.geoalign.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTest {

    private val copy = ReadinessCopy.En
    private val now = 1_700_000_000_000L

    @Test fun neverCheckedSaysSo() {
        assertEquals("Not checked yet", Freshness.checkedLabel(null, now, copy))
        assertNull(Freshness.ageMillis(null, now))
    }

    @Test fun underAMinuteIsMomentsAgo() {
        assertEquals("Checked moments ago", Freshness.checkedLabel(now, now, copy))
        assertEquals("Checked moments ago", Freshness.checkedLabel(now - 45_000, now, copy))
    }

    @Test fun minutesAreSingularAndPlural() {
        assertEquals("Checked 1 min ago", Freshness.checkedLabel(now - 61_000, now, copy))
        assertEquals("Checked 59 min ago", Freshness.checkedLabel(now - 59 * 60_000, now, copy))
    }

    @Test fun hoursRollOverFromMinutes() {
        assertEquals("Checked 1 hr ago", Freshness.checkedLabel(now - 61 * 60_000, now, copy))
    }

    @Test fun beyondADayStopsCounting() {
        assertEquals("Checked over a day ago", Freshness.checkedLabel(now - 25 * 3600_000, now, copy))
    }

    /** Clocks resync. "Checked -3 min ago" reads as a bug, so a future timestamp clamps to zero. */
    @Test fun aFutureTimestampDoesNotProduceNegativeAge() {
        assertEquals("Checked moments ago", Freshness.checkedLabel(now + 180_000, now, copy))
        assertEquals(0L, Freshness.ageMillis(now + 180_000, now))
    }
}
