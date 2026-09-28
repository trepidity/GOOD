package com.trepidity.good.phone.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Command
import com.trepidity.good.model.HealthReply
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SleepSummary
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** What the phone learned from the `/health` ping. */
data class WatchStatus(val reachable: Boolean, val worn: Boolean) {
    /** "Worn and reachable": the watch carries haptics and (for AUTO) the sound. */
    val available: Boolean get() = reachable && worn
}

object PhoneSync {
    private const val TAG = "GoodPhoneSync"

    /** Replies from the watch, fed by [PhoneListenerService]. */
    val healthReplies = MutableSharedFlow<HealthReply>(extraBufferCapacity = 4)

    /** Publish the schedule; the Data Layer delivers it whenever the watch is next in range. */
    suspend fun pushSchedule(context: Context, snapshot: ScheduleSnapshot) {
        val request = PutDataMapRequest.create(DataLayerPaths.SCHEDULE).apply {
            dataMap.putByteArray(DataLayerPaths.KEY_PAYLOAD, SyncCodec.encode(snapshot))
            dataMap.putLong("version", snapshot.version)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Log.i(TAG, "Pushed schedule v${snapshot.version} (${snapshot.entries.size} entries)")
    }

    suspend fun pushSleepSummary(context: Context, summary: SleepSummary) {
        val request = PutDataMapRequest.create(DataLayerPaths.SLEEP_SUMMARY).apply {
            dataMap.putByteArray(DataLayerPaths.KEY_PAYLOAD, SyncCodec.encodeAny(summary))
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /**
     * Dismiss reaches the watch two ways: a message (arrives within a second when connected) and the
     * `/instance/{id}/state` item, which the Data Layer delivers later if the watch is out of range (REVIEW P4).
     */
    suspend fun sendDismiss(context: Context, instance: AlarmInstance, command: Command): Int {
        val request = PutDataMapRequest.create(DataLayerPaths.instanceState(instance.id)).apply {
            dataMap.putByteArray(DataLayerPaths.KEY_PAYLOAD, SyncCodec.encode(command))
            dataMap.putString("state", instance.state.name)
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .onFailure { Log.w(TAG, "State item failed", it) }
        return sendCommand(context, DataLayerPaths.CMD_DISMISS, SyncCodec.encode(command))
    }

    /** Send to every reachable watch running GOOD. Returns how many received it. */
    suspend fun sendCommand(context: Context, path: String, payload: ByteArray): Int {
        val nodes = watchNodes(context)
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it, path, payload).await() }
        return nodes.size
    }

    /**
     * The `/health` ping (REVIEW R8): is a watch reachable, and is it on a wrist? A watch that doesn't answer in
     * [timeoutMs] counts as unavailable, so the phone takes over haptics and sound.
     */
    suspend fun pingWatch(context: Context, timeoutMs: Long = 5_000): WatchStatus = coroutineScope {
        // Subscribe before sending: the reply can arrive before a late collector would see it.
        val reply = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMs * 2) { healthReplies.first() } }
        val sent = withTimeoutOrNull(timeoutMs) {
            runCatching { sendCommand(context, DataLayerPaths.HEALTH, ByteArray(0)) }.getOrDefault(0)
        } ?: 0
        if (sent == 0) {
            reply.cancel()
            return@coroutineScope WatchStatus(reachable = false, worn = false)
        }
        val r = reply.await() ?: return@coroutineScope WatchStatus(reachable = false, worn = false)
        WatchStatus(reachable = true, worn = r.worn)
    }

    suspend fun isWatchReachable(context: Context, timeoutMs: Long = 3_000): Boolean =
        withTimeoutOrNull(timeoutMs) { watchNodes(context).isNotEmpty() } ?: false

    private suspend fun watchNodes(context: Context): List<String> =
        runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await().nodes.map { it.id }
        }.getOrElse {
            Log.w(TAG, "Capability lookup failed", it)
            emptyList()
        }
}
