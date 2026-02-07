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
            dao.getAllMessages().collectLatest { messages ->
                Log.d("MESH", "Database updated, re-broadcasting ${messages.size} messages")
                broadcastMessages(messages)
            }
        }
    }

    fun broadcastMessages(messages: List<SosMessage>) {
        if (messages.isEmpty()) {
            Log.v("MESH", "No messages to broadcast")
            return
        }
        val endpointsToNotify = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
        
        if (endpointsToNotify.isEmpty()) {
            Log.w("MESH", "No connected endpoints to broadcast to. Connected count: 0")
            return
        }
        
        Log.d("MESH", "Broadcasting ${messages.size} messages to ${endpointsToNotify.size} endpoints")
        val json = gson.toJson(messages)
        val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        
        endpointsToNotify.forEach { endpointId ->
            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
                .addOnSuccessListener { Log.v("MESH", "Payload successfully sent to $endpointId") }
                .addOnFailureListener { e -> Log.e("MESH", "Failed to send payload to $endpointId", e) }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(context).startAdvertising(
            myEndpointId, serviceId, connectionLifecycleCallback, options
        ).addOnSuccessListener { Log.i("MESH", "Advertising started successfully") }
         .addOnFailureListener { e -> Log.e("MESH", "Advertising failed to start", e) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(false) 
            .build()
            
        Nearby.getConnectionsClient(context).startDiscovery(
            serviceId, endpointDiscoveryCallback, options
        ).addOnSuccessListener { Log.i("MESH", "Discovery started successfully") }
         .addOnFailureListener { e -> Log.e("MESH", "Discovery failed to start", e) }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i("MESH", "Found endpoint: $endpointId (${info.endpointName}). Requesting connection...")
            Nearby.getConnectionsClient(context).requestConnection(myEndpointId, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e -> Log.e("MESH", "Connection request to $endpointId failed", e) }
        }
        override fun onEndpointLost(endpointId: String) {
            Log.i("MESH", "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i("MESH", "Connection initiated with $endpointId (${info.endpointName}). Accepting...")
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.e("MESH", "Accepting connection from $endpointId failed", e) }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i("MESH", "Successfully connected to $endpointId")
                synchronized(connectedEndpoints) { connectedEndpoints.add(endpointId) }
                // Sync immediately on connection: send all known messages to the new peer
                CoroutineScope(Dispatchers.IO).launch {
                    val messages = dao.getAllMessages().first()
                    if (messages.isNotEmpty()) {
                        Log.d("MESH", "Syncing known messages with new peer $endpointId")
                        broadcastMessages(messages)
                    }
                }
            } else {
                Log.e("MESH", "Connection to $endpointId failed with status: ${result.status.statusMessage}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            Log.w("MESH", "Disconnected from $endpointId")
            synchronized(connectedEndpoints) { connectedEndpoints.remove(endpointId) }
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
                    receivedMessages.forEach { incoming ->
                        if (incoming.senderId == myEndpointId) return@forEach
                        if (currentMessages.none { it.id == incoming.id }) {
                            Log.i("MESH", "Received NEW SOS message: ${incoming.id.take(8)} from ${incoming.senderId}")
                            dao.insert(incoming.copy(isMyMessage = false, hopCount = incoming.hopCount + 1))
                            showSosNotification(incoming)
                        }
                    }
                }
            } catch (e: Exception) { 
                Log.e("MESH", "Error parsing incoming payload from $endpointId", e) 
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.e("MESH", "Payload transfer to $endpointId failed")
            }
        }
    }

    private fun createSosNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Emergency SOS Alerts"
            val descriptionText = "Notifications for received SOS messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("SOS_ALERT_CHANNEL", name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSosNotification(message: SosMessage) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, "SOS_ALERT_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CRITICAL SOS RECEIVED!")
            .setContentText("From: ${message.senderId.takeLast(10)} | Msg: ${message.content}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(message.id.hashCode(), notification)
    }
}
