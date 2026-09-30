package com.trepidity.good.sync

import com.trepidity.good.model.Command
import com.trepidity.good.model.ScheduleSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wearable Data Layer paths shared by the phone and watch apps (see docs/SPEC.md, Architecture). */
object DataLayerPaths {
    const val SCHEDULE = "/schedule"
    const val CMD_DISMISS = "/cmd/dismiss"
    const val CMD_TOGGLE = "/cmd/toggle"
    const val SLEEP_BEDTIME = "/sleep/bedtime"
    const val SLEEP_WAKE = "/sleep/wake"
    const val SLEEP_SIGNAL = "/sleep/signal"
    const val SLEEP_SUMMARY = "/sleep/summary"
    const val INSTANCE_PREFIX = "/instance/"
    const val HEALTH = "/health"
    const val HEALTH_REPLY = "/health/reply"

    /** Key of the byte-array payload inside a DataMap. */
    const val KEY_PAYLOAD = "payload"

    /** Capabilities advertised in res/values/wear.xml of each app. */
    const val CAPABILITY_PHONE = "good_phone"
    const val CAPABILITY_WATCH = "good_watch"

    fun instanceState(instanceId: String) = "/instance/$instanceId/state"
}

object SyncCodec {
    @PublishedApi internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: ScheduleSnapshot): ByteArray = json.encodeToString(snapshot).encodeToByteArray()
    fun decodeSchedule(bytes: ByteArray): ScheduleSnapshot = json.decodeFromString(bytes.decodeToString())

    fun encode(command: Command): ByteArray = json.encodeToString(command).encodeToByteArray()
    fun decodeCommand(bytes: ByteArray): Command = json.decodeFromString(bytes.decodeToString())

    /** Any other @Serializable Data Layer payload (summary, signals, replies, toggles). */
    inline fun <reified T> encodeAny(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()
    inline fun <reified T> decodeAny(bytes: ByteArray): T = json.decodeFromString(bytes.decodeToString())

    /** A device keeps an incoming snapshot only if it is newer than what it already has. */
    fun shouldApply(incoming: ScheduleSnapshot, current: ScheduleSnapshot?): Boolean =
        current == null || incoming.version > current.version
}

/** `/instance/{id}/state` → id, or null for any other path. */
fun instanceIdFromStatePath(path: String?): String? =
    path?.takeIf { it.startsWith(DataLayerPaths.INSTANCE_PREFIX) && it.endsWith("/state") }
        ?.removePrefix(DataLayerPaths.INSTANCE_PREFIX)?.removeSuffix("/state")

object ScheduleMerge {
    /**
     * The watch's view of a new snapshot: the phone's entries, except that an occurrence the watch already
     * closed (dismissed or silenced here) stays closed. Otherwise a snapshot sent before the phone heard of the
     * watch's dismiss would re-arm the backup alarm at T (REVIEW R4).
     */
    fun merge(incoming: ScheduleSnapshot, local: ScheduleSnapshot?): ScheduleSnapshot {
        val closedHere = local?.entries.orEmpty().filter { it.instance.state.isTerminal }.associateBy { it.instance.id }
        return incoming.copy(entries = incoming.entries.map { e -> closedHere[e.instance.id]?.let { e.copy(instance = it.instance) } ?: e })
    }
}
