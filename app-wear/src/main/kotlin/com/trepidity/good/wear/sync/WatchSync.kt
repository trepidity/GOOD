package com.trepidity.good.wear.sync

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.trepidity.good.model.Command
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import kotlinx.coroutines.tasks.await

object WatchSync {
    /** Send to the phone; returns false if it isn't reachable (the phone catches up from the store later). */
    suspend fun sendToPhone(context: Context, path: String, command: Command): Boolean {
        val nodes = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await().nodes
        }.getOrDefault(emptySet())
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, path, SyncCodec.encode(command)).await() }
        return nodes.isNotEmpty()
    }
}
