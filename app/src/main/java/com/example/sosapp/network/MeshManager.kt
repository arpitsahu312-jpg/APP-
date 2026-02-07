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
    
    // Consistent ID for this device session
    val myEndpointId = "Dev_${UUID.randomUUID().toString().take(4)}_${android.os.Build.MODEL}" 

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
                Log.d("MESH", "Database changed, broadcasting ${messages.size} messages")
                broadcastMessages(messages)
            }
        }
    }

    private fun broadcastMessages(messages: List<SosMessage>) {
        if (messages.isEmpty()) return
        
        val endpointsToNotify = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
        if (endpointsToNotify.isEmpty()) {
            Log.v("MESH", "No connected endpoints to broadcast to.")
            return
        }
        
        val json = gson.toJson(messages)
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        
        endpointsToNotify.forEach { endpointId ->
            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.v("MESH", "Payload successfully sent to $endpointId")
                }
                .addOnFailureListener { e -> 
                    Log.e("MESH", "Failed to send payload to $endpointId: $e") 
                }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnSuccessListener {
            Log.i("MESH", "Advertising started successfully.")
        }.addOnFailureListener { e -> 
            Log.e("MESH", "Advertising failed to start: $e") 
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnSuccessListener {
            Log.i("MESH", "Discovery started successfully.")
        }.addOnFailureListener { e -> 
            Log.e("MESH", "Discovery failed to start: $e") 
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i("MESH", "Discovered endpoint: $endpointId (${info.endpointName}). Requesting connection...")
            Nearby.getConnectionsClient(context).requestConnection(myEndpointId, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e -> Log.e("MESH", "Connection request to $endpointId failed: $e") }
        }
        override fun onEndpointLost(endpointId: String) {
            Log.i("MESH", "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i("MESH", "Connection initiated with $endpointId (${info.endpointName}). Accepting...")
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.e("MESH", "Accept connection for $endpointId failed: $e") }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i("MESH", "Successfully connected to $endpointId")
                synchronized(connectedEndpoints) {
                    connectedEndpoints.add(endpointId)
                }
                // Immediate sync upon connection
                CoroutineScope(Dispatchers.IO).launch {
                    val messages = dao.getAllMessages().first()
                    if (messages.isNotEmpty()) {
                        broadcastMessages(messages)
                    }
                }
            } else {
                Log.e("MESH", "Connection result for $endpointId: FAILURE (${result.status.statusMessage})")
            }
        }
        override fun onDisconnected(endpointId: String) {
            Log.w("MESH", "Disconnected from endpoint: $endpointId")
            synchronized(connectedEndpoints) {
                connectedEndpoints.remove(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = String(bytes, StandardCharsets.UTF_8)
            
            try {
                val receivedMessages = gson.fromJson(json, Array<SosMessage>::class.java)
                Log.d("MESH", "Received ${receivedMessages.size} messages from $endpointId")
                
                CoroutineScope(Dispatchers.IO).launch {
                    val currentMessages = dao.getAllMessages().first()
                    var anyNew = false
                    
                    receivedMessages.forEach { incoming ->
                        // 1. Skip if we are the original author
                        if (incoming.senderId == myEndpointId) return@forEach

                        // 2. Check if this is a new message (by UUID)
                        val existing = currentMessages.find { it.id == incoming.id }
                        
                        if (existing == null) {
                            // 3. New message discovered! Increment hop and save.
                            val toSave = incoming.copy(
                                isMyMessage = false,
                                hopCount = incoming.hopCount + 1
                            )
                            dao.insert(toSave)
                            anyNew = true
                            Log.i("MESH", ">>> RELAY SUCCESS! Hopped message ${toSave.id.take(8)} from ${incoming.senderId}. New Hop Count: ${toSave.hopCount}")
                        } else {
                            // Log very briefly for duplicates
                            Log.v("MESH", "Duplicate message received: ${incoming.id.take(8)}")
                        }
                    }
                    
                    // If we found new messages, the observer (observeMessages) will 
                    // automatically trigger a broadcast to all our OTHER peers.
                }
            } catch (e: Exception) { 
                Log.e("MESH", "Error processing incoming payload: $e") 
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.e("MESH", "Payload transfer to $endpointId failed.")
            }
        }
    }
}
