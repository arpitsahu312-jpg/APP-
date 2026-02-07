package com.example.sosapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    
    lateinit var database: AppDatabase
    lateinit var meshManager: MeshManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = AppDatabase.getDatabase(this)
        meshManager = MeshManager(this, database.sosDao())
        
        try {
            meshManager.startMesh()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            SOSAppTheme {
                SOSScreen(database, meshManager, this)
            }
        }
    }
    
    fun getLastLocation(onLocation: (Double, Double) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            onLocation(28.7041, 77.1025)
            return
        }
        
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) onLocation(loc.latitude, loc.longitude)
                else onLocation(28.7041, 77.1025)
            }
    }

    fun openMap() {
        // Linking to the Map functionality set in the backend/OCEN module
        try {
            val intent = Intent(this, com.example.OCEN.MainActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Map Activity not found", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerBuzzer() {
        Toast.makeText(this, "High Frequency Buzzer Activated!", Toast.LENGTH_SHORT).show()
        
        // Vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
        }

        // High Frequency Sound (15kHz - piercing and high pitch)
        playHighFrequencyTone(15000.0, 3000)

        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            vibrator.cancel()
        }
    }

    private fun playHighFrequencyTone(frequency: Double, durationMs: Int) {
        val sampleRate = 44100
        val numSamples = (durationMs * sampleRate) / 1000
        val generatedSnd = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val sample = Math.sin(2.0 * Math.PI * i / (sampleRate / frequency))
            generatedSnd[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }

        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(generatedSnd.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(generatedSnd, 0, generatedSnd.size)
            audioTrack.play()

            // Release resources after playing
            CoroutineScope(Dispatchers.IO).launch {
                delay(durationMs.toLong() + 500)
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun openManual() {
        val intent = Intent(this, ManualActivity::class.java)
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SOSScreen(database: AppDatabase, meshManager: MeshManager, activity: MainActivity) {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    var isSosActive by remember { mutableStateOf(false) }
    val messageList by database.sosDao().getAllMessages().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("OFFLINE MESH SOS", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp) },
                actions = {
                    IconButton(onClick = { activity.openMap() }) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Map", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { activity.openManual() }) {
                        Icon(Icons.Default.MenuBook, contentDescription = "Manual", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { activity.triggerBuzzer() }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Buzzer", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SOSButtonWithProgress(
                    isActive = isSosActive,
                    onToggle = {
                        isSosActive = !isSosActive
                        vibrateDevice(vibrator)
                        
                        if (isSosActive) {
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
                        } else {
                            Toast.makeText(context, "SOS DEACTIVATED", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Text(
                "  LIVE NETWORK LOG", 
                fontWeight = FontWeight.Bold, 
                modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer).fillMaxWidth().padding(8.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            LazyColumn(
                modifier = Modifier
                    .height(250.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
            ) {
                items(messageList) { msg ->
                    val color = if (msg.isMyMessage) Color(0xFF00E5FF) else Color(0xFFFFEA00)
                    val prefix = if (msg.isMyMessage) "SENT ->" else "<- RECV"
                    
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "$prefix [${msg.senderId}]",
                            color = color,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Msg: ${msg.content} | Hops: ${msg.hopCount}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "GPS: ${msg.latitude}, ${msg.longitude}",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun SOSButtonWithProgress(isActive: Boolean, onToggle: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (progress == 0f) snap() else tween(durationMillis = 2000, easing = LinearEasing),
        label = "ProgressAnimation"
    )

    LaunchedEffect(animatedProgress) {
        if (animatedProgress >= 1f) {
            onToggle()
            progress = 0f
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            // Circular Progress Indicator
            Canvas(modifier = Modifier.size(220.dp)) {
                drawArc(
                    color = Color.LightGray.copy(alpha = 0.3f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = if (isActive) Color.Green else Color.Red,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Surface(
                modifier = Modifier
                    .size(180.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                progress = 1f
                                try {
                                    awaitRelease()
                                } finally {
                                    progress = 0f
                                }
                            }
                        )
                    },
                shape = CircleShape,
                color = if (isActive) Color(0xFFD32F2F) else Color(0xFF424242),
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isActive) "SOS ACTIVE" else "HOLD 2S\nFOR SOS",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

fun vibrateDevice(vibrator: Vibrator) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        vibrator.vibrate(300)
    }
}

@Composable
fun SOSAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF81C784),
            secondary = Color(0xFF4DB6AC),
            surfaceVariant = Color(0xFF37474F),
            background = Color(0xFF121212)
        ),
        content = content
    )
}
