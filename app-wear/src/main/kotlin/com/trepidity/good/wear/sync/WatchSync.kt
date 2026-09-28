package com.trepidity.good.wear.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Command
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch → phone. Messages that can't be delivered now wait in a device-protected outbox and are flushed when
 * the phone is next reachable (REVIEW U8), so a bedtime or sleep signal recorded out of range isn't lost.
 */
object WatchSync {
    private const val TAG = "GoodWatchSync"
    private const val PREFS = "good_watch_outbox"
    private const val KEY = "queue"
    private const val MAX_QUEUED = 500
    private val mutex = Mutex()

    /** Sends now if the phone is reachable, else queues. Returns true if delivered now. */
    suspend fun send(context: Context, path: String, payload: ByteArray): Boolean = mutex.withLock {
        val delivered = deliverAll(context, listOf(path to payload)).isEmpty()
        if (!delivered) enqueue(context, path, payload)
        delivered
    }

    /** Dismiss: a message now, and the `/instance/{id}/state` item the Data Layer delivers whenever it can (REVIEW P4). */
    suspend fun sendDismiss(context: Context, instance: AlarmInstance, command: Command): Boolean {
        runCatching {
            val request = PutDataMapRequest.create(DataLayerPaths.instanceState(instance.id)).apply {
                dataMap.putByteArray(DataLayerPaths.KEY_PAYLOAD, SyncCodec.encode(command))
                dataMap.putString("state", instance.state.name)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "State item failed", it) }
        return send(context, DataLayerPaths.CMD_DISMISS, SyncCodec.encode(command))
    }

    /** Called when the phone becomes reachable. */
    suspend fun flush(context: Context) = mutex.withLock {
        val queued = load(context)
        if (queued.isEmpty()) return@withLock
        val left = deliverAll(context, queued)
        save(context, left)
        Log.i(TAG, "Outbox flushed: ${queued.size - left.size} sent, ${left.size} left")
    }

    fun queuedCount(context: Context): Int = load(context).size

    /** Returns what could not be delivered. */
    private suspend fun deliverAll(context: Context, items: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val nodes = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await().nodes
        }.getOrDefault(emptySet())
        if (nodes.isEmpty()) return items
        val client = Wearable.getMessageClient(context)
        return items.filterNot { (path, payload) ->
            runCatching { nodes.forEach { client.sendMessage(it.id, path, payload).await() } }.isSuccess
        }
    }

    private fun enqueue(context: Context, path: String, payload: ByteArray) = save(context, (load(context) + (path to payload)).takeLast(MAX_QUEUED))

    private fun load(context: Context): List<Pair<String, ByteArray>> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("p") to Base64.decode(o.getString("d"), Base64.NO_WRAP)
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<Pair<String, ByteArray>>) {
        val arr = JSONArray()
        items.forEach { (p, d) -> arr.put(JSONObject().put("p", p).put("d", Base64.encodeToString(d, Base64.NO_WRAP))) }
        prefs(context).edit(commit = true) { putString(KEY, arr.toString()) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
