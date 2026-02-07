package com.example.sosapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.sosapp.data.AppDatabase
import com.example.sosapp.data.SosMessage
import com.example.sosapp.network.MeshManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    
    // BACKEND VARIABLES
    lateinit var database: AppDatabase
    lateinit var meshManager: MeshManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Initialize Backend
        database = AppDatabase.getDatabase(this)
        meshManager = MeshManager(this, database.sosDao())
        
        // 2. Start the Mesh (Advertising & Scanning)
        // NOTE: Manually grant "Nearby Devices" & "Location" permission in Settings for the demo!
        try {
            meshManager.startMesh()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            SOSAppTheme {
                // Pass DB and Manager to the screen
                SOSScreen(database, meshManager, this)
            }
        }
    }
    
    // Helper to get GPS
    fun getLastLocation(onLocation: (Double, Double) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            onLocation(28.7041, 77.1025) // Fallback Default (Delhi)
            return
        }
        
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) onLocation(loc.latitude, loc.longitude)
                else onLocation(28.7041, 77.1025)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SOSScreen(database: AppDatabase, meshManager: MeshManager, activity: MainActivity) {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    var isSosActive by remember { mutableStateOf(false) }
    
    // OBSERVE DATABASE (Live Updates for Judges)
    val messageList by database.sosDao().getAllMessages().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mesh SOS Network", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { /* Launch Map Activity */ }) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Map")
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Buzzer")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            
            // 1. SOS BUTTON AREA
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SOSButton(
                    isActive = isSosActive,
                    onToggle = {
                        isSosActive = !isSosActive
                        vibrateDevice(vibrator)
                        
                        if (isSosActive) {
                            // ACTIVATE SOS
                            activity.getLastLocation { lat, long ->
                                val msg = SosMessage(
                                    id = UUID.randomUUID().toString(),
                                    senderId = android.os.Build.MODEL,
                                    content = "CRITICAL SOS!",
                                    latitude = lat,
                                    longitude = long,
                                    timestamp = System.currentTimeMillis(),
                                    isMyMessage = true,
                                    hopCount = 0
                                )
                                CoroutineScope(Dispatchers.IO).launch {
                                    database.sosDao().insert(msg)
                                }
                                Toast.makeText(context, "SOS BROADCASTING!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // 2. JUDGE'S DEBUG CONSOLE (Visual Proof of Hopping)
            HorizontalDivider(color = Color.Black, thickness = 2.dp)
            Text(
                "  LIVE NETWORK LOG (For Judges)", 
                fontWeight = FontWeight.Bold, 
                modifier = Modifier.background(Color.Black).fillMaxWidth().padding(4.dp),
                color = Color.Green
            )
            
            LazyColumn(
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                items(messageList) { msg ->
                    val color = if (msg.isMyMessage) Color.Cyan else Color.Yellow
                    val prefix = if (msg.isMyMessage) "SENT ->" else "<- RECV"
                    
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "$prefix [${msg.senderId}]",
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Msg: ${msg.content} | Hops: ${msg.hopCount}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "GPS: ${msg.latitude}, ${msg.longitude}",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        HorizontalDivider(color = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun SOSButton(isActive: Boolean, onToggle: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isPressing by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(200.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressing = true
                            val job = scope.launch {
                                delay(2000) // 2 second hold
                                if (isPressing) onToggle()
                            }
                            try { awaitRelease() } 
                            finally { isPressing = false; job.cancel() }
                        }
                    )
                },
            shape = CircleShape,
            color = if (isActive) Color.Red else Color.Gray,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isActive) "SOS ON" else "HOLD 2S\nFOR SOS",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

fun vibrateDevice(vibrator: Vibrator) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        vibrator.vibrate(200)
    }
}

@Composable
fun SOSAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1B5E20),
            primaryContainer = Color(0xFFC8E6C9)
        ),
        content = content
    )
}