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
    private val myEndpointId = "Dev_${UUID.randomUUID().toString().take(4)}_${android.os.Build.MODEL}" 

    private val connectedEndpoints = mutableSetOf<String>()
    private var observationJob: Job? = null

    fun startMesh() {
        Log.i("MESH", "Starting Mesh Service as: $myEndpointId")
        startAdvertising()
        startDiscovery()
        observeMessages()
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
        if (connectedEndpoints.isEmpty() || messages.isEmpty()) return
        
        val json = gson.toJson(messages)
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        
        synchronized(connectedEndpoints) {
            connectedEndpoints.forEach { endpointId ->
                Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
                    .addOnSuccessListener {
                        Log.d("MESH", "Payload sent to $endpointId")
                    }
                    .addOnFailureListener { e -> 
                        Log.e("MESH", "Send fail to $endpointId: $e") 
                    }
            }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnSuccessListener {
            Log.i("MESH", "Advertising started...")
        }.addOnFailureListener { e -> 
            Log.e("MESH", "Advertising failed: $e") 
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnSuccessListener {
            Log.i("MESH", "Discovery started...")
        }.addOnFailureListener { e -> 
            Log.e("MESH", "Discovery failed: $e") 
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i("MESH", "Endpoint found: $endpointId (${info.endpointName})")
            Nearby.getConnectionsClient(context).requestConnection(myEndpointId, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {
            Log.i("MESH", "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i("MESH", "Connection initiated with $endpointId")
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i("MESH", "Connected to $endpointId successfully")
                synchronized(connectedEndpoints) {
                    connectedEndpoints.add(endpointId)
                }
                // Sync current database state to the new connection
                CoroutineScope(Dispatchers.IO).launch {
                    val messages = dao.getAllMessages().first()
                    if (messages.isNotEmpty()) {
                        broadcastMessages(messages)
                    }
                }
            } else {
                Log.e("MESH", "Connection to $endpointId failed: ${result.status.statusMessage}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            Log.w("MESH", "Disconnected from $endpointId")
            synchronized(connectedEndpoints) {
                connectedEndpoints.remove(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val json = String(bytes, StandardCharsets.UTF_8)
                try {
                    val receivedMessages = gson.fromJson(json, Array<SosMessage>::class.java)
                    Log.d("MESH", "Received ${receivedMessages.size} messages from $endpointId")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val currentMessages = dao.getAllMessages().first()
                        
                        receivedMessages.forEach { incoming ->
                            if (incoming.senderId == myEndpointId) return@forEach

                            val existing = currentMessages.find { it.id == incoming.id }
                            
                            if (existing == null) {
                                val toSave = incoming.copy(
                                    isMyMessage = false,
                                    hopCount = incoming.hopCount + 1
                                )
                                dao.insert(toSave)
                                Log.v("MESH", "HOP DETECTED! Msg ID: ${toSave.id} | From: ${incoming.senderId} | New Hop Count: ${toSave.hopCount}")
                            }
                        }
                    }
                } catch (e: Exception) { 
                    Log.e("MESH", "Payload parse fail: $e") 
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
