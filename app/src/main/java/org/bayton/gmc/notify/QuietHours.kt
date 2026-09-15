package org.bayton.gmc.notify

/** Quiet-hours window test, including the overnight-wrap case. */
object QuietHours {
    /** True when [minute]-of-day falls inside [start, end), handling an overnight wrap (start > end). */
    fun contains(minute: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        return if (start < end) minute in start until end else minute >= start || minute < end
    }
}
