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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

class MeshManager(private val context: Context, private val dao: SosDao) {

    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.example.sosapp.SERVICE_ID"
    private val gson = Gson()
    
    val myEndpointId = "Dev_${UUID.randomUUID().toString().take(4)}_${android.os.Build.MODEL}" 

    private val connectedEndpoints = mutableSetOf<String>()
    private var observationJob: Job? = null

    fun startMesh() {
        Log.i("MESH", "Starting Mesh Service as: $myEndpointId")
        stopMesh() 
        startAdvertising()
        startDiscovery()
        observeMessages()
    }

    fun stopMesh() {
        Log.i("MESH", "Stopping Mesh Service")
        Nearby.getConnectionsClient(context).stopAdvertising()
        Nearby.getConnectionsClient(context).stopDiscovery()
        Nearby.getConnectionsClient(context).stopAllEndpoints()
        observationJob?.cancel()
        synchronized(connectedEndpoints) {
            connectedEndpoints.clear()
        }
    }

    private fun observeMessages() {
        observationJob?.cancel()
        observationJob = CoroutineScope(Dispatchers.IO).launch {
            dao.getAllMessages().collectLatest { messages ->
                broadcastMessages(messages)
            }
        }
    }

    private fun broadcastMessages(messages: List<SosMessage>) {
        if (messages.isEmpty()) return
        val endpointsToNotify = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
        if (endpointsToNotify.isEmpty()) return
        
        val json = gson.toJson(messages)
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        
        endpointsToNotify.forEach { endpointId ->
            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnFailureListener { e -> Log.e("MESH", "Adv failed: $e") }
    }

    private fun startDiscovery() {
        // DISABLING Low Power mode for maximum range/sensitivity
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(false) 
            .build()
            
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnFailureListener { e -> Log.e("MESH", "Discovery failed: $e") }
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
                synchronized(connectedEndpoints) { connectedEndpoints.add(endpointId) }
                // Sync immediately on connection
                CoroutineScope(Dispatchers.IO).launch {
                    val messages = dao.getAllMessages().first()
                    if (messages.isNotEmpty()) broadcastMessages(messages)
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            synchronized(connectedEndpoints) { connectedEndpoints.remove(endpointId) }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, StandardCharsets.UTF_8)
            try {
                val receivedMessages = gson.fromJson(json, Array<SosMessage>::class.java)
                CoroutineScope(Dispatchers.IO).launch {
                    val currentMessages = dao.getAllMessages().first()
                    receivedMessages.forEach { incoming ->
                        if (incoming.senderId == myEndpointId) return@forEach
                        if (currentMessages.none { it.id == incoming.id }) {
                            dao.insert(incoming.copy(isMyMessage = false, hopCount = incoming.hopCount + 1))
                        }
                    }
                }
            } catch (e: Exception) { Log.e("MESH", "Payload error: $e") }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
