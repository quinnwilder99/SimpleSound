package com.simplesound.app.util

import java.util.concurrent.TimeUnit

/** Formats a duration in ms as m:ss or h:mm:ss. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "12 tracks" / "1 track". */
fun trackCountLabel(count: Int): String = "$count " + if (count == 1) "track" else "tracks"
