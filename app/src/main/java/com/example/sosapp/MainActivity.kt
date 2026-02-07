package com.example.sosapp

import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SOSAppTheme {
                SOSScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SOSScreen() {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    var isSosActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mesh SOS Network", fontWeight = FontWeight.Bold) },
                actions = {
                    // Map Icon
                    IconButton(onClick = { onMapClick(context) }) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Map")
                    }
                    // Buzzer Icon
                    IconButton(onClick = { onBuzzerClick(context) }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Buzzer")
                    }
                    // Manual Icon
                    IconButton(onClick = { onManualClick(context) }) {
                        Icon(Icons.Default.Info, contentDescription = "Manual")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            SOSButton(
                isActive = isSosActive,
                onToggle = {
                    isSosActive = !isSosActive
                    vibrateDevice(vibrator)
                    if (isSosActive) {
                        onSosActivated(context)
                    } else {
                        onSosDeactivated(context)
                    }
                }
            )
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
                                delay(2000) // 2 second long press
                                if (isPressing) {
                                    onToggle()
                                }
                            }
                            try {
                                awaitRelease()
                            } finally {
                                isPressing = false
                                job.cancel()
                            }
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
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (isPressing) "Activating..." else if (isActive) "SOS Active" else "System Offline",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isActive) Color.Red else Color.Gray
        )
    }
}

// --- Backend Integration Hooks ---

fun onMapClick(context: Context) {
    // TODO: Link to backend map logic
    // Example: context.startActivity(Intent(context, MapActivity::class.java))
}

fun onBuzzerClick(context: Context) {
    // TODO: Link to backend buzzer sound logic
}

fun onManualClick(context: Context) {
    // TODO: Link to backend manual/disaster info logic
}

fun onSosActivated(context: Context) {
    // TODO: Logic for SOS activation in mesh network
}

fun onSosDeactivated(context: Context) {
    // TODO: Logic for SOS deactivation
}

private fun vibrateDevice(vibrator: Vibrator) {
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
