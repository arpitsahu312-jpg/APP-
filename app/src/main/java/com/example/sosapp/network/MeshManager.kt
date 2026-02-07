package com.example.sosapp.network

import android.content.Context
import android.util.Log
import com.example.sosapp.data.SosDao
import com.example.sosapp.data.SosMessage
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class MeshManager(private val context: Context, private val dao: SosDao) {

    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.example.sosapp.SERVICE_ID"
    private val gson = Gson()
    private val myEndpointId = android.os.Build.MODEL 

    fun startMesh() {
        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnFailureListener { e -> Log.e("MESH", "Adv fail: $e") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnFailureListener { e -> Log.e("MESH", "Disc fail: $e") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Nearby.getConnectionsClient(context).requestConnection(myEndpointId, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                shareData(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {}
    }

    private fun shareData(endpointId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val allMessages = dao.getAllMessages().first()
            val json = gson.toJson(allMessages)
            val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val json = String(bytes, StandardCharsets.UTF_8)
                try {
                    val messages = gson.fromJson(json, Array<SosMessage>::class.java)
                    CoroutineScope(Dispatchers.IO).launch {
                        messages.forEach { msg ->
                            // If the message originated from this device, ignore it
                            if (msg.senderId == myEndpointId) return@forEach

                            // Mark as received (not my message) and increase hop count
                            val received = msg.copy(isMyMessage = false, hopCount = msg.hopCount + 1)
                            dao.insert(received)
                        }
                    }
                } catch (e: Exception) { Log.e("MESH", "Parse fail: $e") }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}