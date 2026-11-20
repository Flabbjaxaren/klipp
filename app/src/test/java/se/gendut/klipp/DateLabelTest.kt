package se.gendut.klipp

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The projects page's dates go by calendar days, not by 24 hours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv")
class DateLabelTest {
    @Test fun yesterdayEveningIsYesterdayTheNextMorning() {
        val zone = java.time.ZoneId.systemDefault()
        fun at(d: Int, h: Int, m: Int) = java.time.LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val now = at(20, 9, 40)
        assertTrue(dateLabel(at(19, 22, 35), now), dateLabel(at(19, 22, 35), now).startsWith("igår"))
        assertTrue(dateLabel(at(20, 0, 5), now), dateLabel(at(20, 0, 5), now).startsWith("idag"))
        assertTrue(dateLabel(at(18, 23, 0), now), dateLabel(at(18, 23, 0), now).startsWith("18"))
    }
}
