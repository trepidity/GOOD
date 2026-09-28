package com.trepidity.good.phone.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.trepidity.good.model.Command
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object PhoneSync {
    private const val TAG = "GoodPhoneSync"

    /** Publish the schedule; the Data Layer delivers it whenever the watch is next in range. */
    suspend fun pushSchedule(context: Context, snapshot: ScheduleSnapshot) {
        val request = PutDataMapRequest.create(DataLayerPaths.SCHEDULE).apply {
            dataMap.putByteArray(DataLayerPaths.KEY_PAYLOAD, SyncCodec.encode(snapshot))
            dataMap.putLong("version", snapshot.version)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Log.i(TAG, "Pushed schedule v${snapshot.version} (${snapshot.entries.size} entries)")
    }

    /** Send a command to every reachable watch running GOOD. Returns how many received it. */
    suspend fun sendCommand(context: Context, path: String, command: Command): Int {
        val nodes = watchNodes(context)
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it, path, SyncCodec.encode(command)).await() }
        return nodes.size
    }

    /** Is a watch with GOOD installed reachable right now? (Worn-state check via /health comes in M2.) */
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
