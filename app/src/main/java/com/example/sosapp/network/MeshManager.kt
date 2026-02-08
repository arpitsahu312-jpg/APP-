package com.example.sosapp.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sosapp.data.SosDao
import com.example.sosapp.data.SosMessage
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.util.UUID

class MeshManager(private val context: Context, private val dao: SosDao) {

    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.example.sosapp.SERVICE_ID"
    private val gson = Gson()
    
    val myEndpointId = "Dev_${UUID.randomUUID().toString().take(4)}_${android.os.Build.MODEL}" 

    private val connectedEndpoints = mutableSetOf<String>()
    private var observationJob: Job? = null
    private var lastBroadcastHash: Int = 0

    init {
        createSosNotificationChannel()
    }

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
            dao.getUnuploadedMessages().collectLatest { messages ->
                if (messages.isEmpty()) return@collectLatest
                
                val currentHash = messages.hashCode()
                if (currentHash == lastBroadcastHash) return@collectLatest
                
                // Optimized delay for faster propagation
                delay(300) 
                
                lastBroadcastHash = currentHash
                Log.d("MESH", "Broadcasting ${messages.size} unuploaded messages...")
                broadcastMessages(messages)
            }
        }
    }

    fun broadcastMessages(messages: List<SosMessage>) {
        val endpointsToNotify = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
        
        if (endpointsToNotify.isEmpty()) return
        
        val json = gson.toJson(messages)
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        
        Nearby.getConnectionsClient(context).sendPayload(endpointsToNotify, payload)
            .addOnFailureListener { e -> Log.e("MESH", "Broadcast failed: ${e.message}") }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnSuccessListener { Log.i("MESH", "Advertising started") }
         .addOnFailureListener { e -> 
            Log.e("MESH", "Advertising failed, retrying...")
            CoroutineScope(Dispatchers.Main).launch { delay(2000); startAdvertising() }
         }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(false) 
            .build()
            
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnSuccessListener { Log.i("MESH", "Discovery started") }
         .addOnFailureListener { e -> 
            Log.e("MESH", "Discovery failed, retrying...")
            CoroutineScope(Dispatchers.Main).launch { delay(2000); startDiscovery() }
         }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i("MESH", "Found: $endpointId. Connecting...")
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
                Log.i("MESH", "Connected to $endpointId")
                synchronized(connectedEndpoints) { connectedEndpoints.add(endpointId) }
                CoroutineScope(Dispatchers.IO).launch {
                    val messages = dao.getUnuploadedMessages().first()
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
                    val existingMessages = dao.getAllMessages().first()
                    val existingIds = existingMessages.map { it.id }.toSet()
                    
                    receivedMessages.forEach { incoming ->
                        if (incoming.senderId == myEndpointId) return@forEach
                        
                        if (incoming.isUploaded) {
                            dao.markAsUploaded(incoming.id)
                            return@forEach
                        }
                        
                        if (!existingIds.contains(incoming.id)) {
                            Log.i("MESH", "Syncing New Message")
                            dao.insert(incoming.copy(isMyMessage = false, hopCount = incoming.hopCount + 1))
                            showSosNotification(incoming)
                        } else {
                            // Even if message exists, check if we need to update uploaded status locally
                            val existing = existingMessages.find { it.id == incoming.id }
                            if (existing != null && !existing.isUploaded && incoming.isUploaded) {
                                dao.markAsUploaded(incoming.id)
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.e("MESH", "Payload error") }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun createSosNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SOS_ALERT_CHANNEL", "Emergency SOS", NotificationManager.IMPORTANCE_HIGH)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showSosNotification(message: SosMessage) {
        val notification = NotificationCompat.Builder(context, "SOS_ALERT_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS RECEIVED!")
            .setContentText("Incoming Emergency Message")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(message.id.hashCode(), notification)
    }
}
