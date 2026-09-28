package com.trepidity.good.wear

import android.content.Context
import android.text.format.DateFormat
import com.trepidity.good.model.WakeProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Short LCD strings shared by the watch app, tile and complications. */
object WatchFormat {
    private val H24 = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
    private val H12 = DateTimeFormatter.ofPattern("h:mm", Locale.ROOT)
    private val AMPM = DateTimeFormatter.ofPattern("a", Locale.US)

    /** Local wall-clock time, "6:30" or "18:30" per the device's 12/24-hour setting. */
    fun clock(context: Context, epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(if (DateFormat.is24HourFormat(context)) H24 else H12)

    /** "AM"/"PM" on a 12-hour device, null on a 24-hour one. */
    fun meridiem(context: Context, epochMs: Long): String? =
        if (DateFormat.is24HourFormat(context)) null
        else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(AMPM).uppercase(Locale.ROOT)

    /** Minutes as a chrono readout: 462 → "7:42". */
    fun hoursMinutes(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    /** Seconds as an interval: 600 → "10:00". */
    fun minutesSeconds(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    /** "IN 7:20" under a day, "IN 2D 3H" beyond, "NOW" once due. Rounds up so it never reads "IN 0:00" early. */
    fun countdown(nowMs: Long, atMs: Long): String {
        val minutes = ((atMs - nowMs + 59_999) / 60_000).toInt()
        return when {
            minutes <= 0 -> "NOW"
            minutes < 24 * 60 -> "IN ${hoursMinutes(minutes)}"
            else -> "IN ${minutes / (24 * 60)}D ${minutes / 60 % 24}H"
        }
    }

    /** Spoken form of [countdown]. */
    fun countdownSpeech(nowMs: Long, atMs: Long): String {
        val minutes = ((atMs - nowMs + 59_999) / 60_000).toInt()
        return if (minutes <= 0) "due now" else "in ${minutes / 60} hours ${minutes % 60} minutes"
    }

    /** "AL1".."AL4"; channel 0 is the phone's test alarm. */
    fun channel(alarmId: Long): String = if (alarmId in 1..4) "AL$alarmId" else "TST"

    /** First word of the profile name, as it fits the LCD: "Heavy sleeper" → "HEAVY". */
    fun profile(profile: WakeProfile): String = profile.name.substringBefore(' ').uppercase(Locale.ROOT).take(8)
}
