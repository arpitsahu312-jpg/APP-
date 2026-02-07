package com.example.sosapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ManualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SOSAppTheme {
                ManualScreen(onBack = { finish() })
            }
        }
    }
}

data class ManualItem(val title: String, val steps: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(onBack: () -> Unit) {
    val manualData = listOf(
        ManualItem(
            "Natural Disasters: Earthquake",
            listOf(
                "DROP down onto your hands and knees.",
                "COVER your head and neck under a sturdy table or desk.",
                "HOLD ON to your shelter until the shaking stops.",
                "Stay away from glass, windows, outside doors and walls."
            )
        ),
        ManualItem(
            "Natural Disasters: Flood",
            listOf(
                "Move to higher ground immediately.",
                "Do not walk, swim, or drive through flood waters.",
                "Stay off bridges over fast-moving water.",
                "Evacuate if told to do so."
            )
        ),
        ManualItem(
            "Pandemic Guidelines",
            listOf(
                "Wear a mask in public indoor spaces.",
                "Maintain at least 6 feet of distance from others.",
                "Wash your hands frequently with soap and water.",
                "Get vaccinated and stay up to date with boosters.",
                "Monitor your health daily and stay home if sick."
            )
        ),
        ManualItem(
            "Emergency Contacts",
            listOf(
                "Police: 100",
                "Fire: 101",
                "Ambulance: 102",
                "Disaster Management: 108"
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Manual", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(manualData) { item ->
                ManualSection(item)
            }
        }
    }
}

@Composable
fun ManualSection(item: ManualItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            item.steps.forEach { step ->
                Text(
                    text = "• $step",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
