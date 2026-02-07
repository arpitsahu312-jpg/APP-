package com.example.OCEN 

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.sosapp.data.AppDatabase
import com.example.sosapp.data.SosMessage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private var map: MapView? = null
    private var locationOverlay: MyLocationNewOverlay? = null
    private lateinit var database: AppDatabase
    private val markers = mutableMapOf<String, Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = AppDatabase.getDatabase(this)

        try {
            val ctx = applicationContext
            Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue = packageName

            setContentView(R.layout.activity_main)

            map = findViewById(R.id.map)
            
            if (map != null) {
                map!!.setTileSource(TileSourceFactory.MAPNIK)
                map!!.setMultiTouchControls(true)
                map!!.controller.setZoom(15.0)
                
                locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
                locationOverlay!!.enableMyLocation()
                locationOverlay!!.enableFollowLocation()
                map!!.overlays.add(locationOverlay)

                centerMapOnCurrentLocation()
                observeMessages()

            } else {
                Toast.makeText(this, "CRITICAL ERROR: Map View not found!", Toast.LENGTH_LONG).show()
            }

            val downloadBtn = findViewById<Button>(R.id.btnDownload)
            downloadBtn?.text = "Center Map"
            downloadBtn?.setOnClickListener {
                centerMapOnCurrentLocation()
            }

            checkPermissions()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            database.sosDao().getAllMessages().collectLatest { messageList ->
                updateMapMarkers(messageList)
            }
        }
    }

    private fun updateMapMarkers(messageList: List<SosMessage>) {
        val currentMap = map ?: return
        
        // Remove markers that are no longer in the list
        val messageIds = messageList.map { it.id }.toSet()
        val toRemove = markers.keys.filter { it !in messageIds }
        toRemove.forEach { id ->
            currentMap.overlays.remove(markers[id])
            markers.remove(id)
        }

        // Add or update markers
        messageList.forEach { msg ->
            val point = GeoPoint(msg.latitude, msg.longitude)
            val marker = markers.getOrPut(msg.id) {
                val newMarker = Marker(currentMap)
                currentMap.overlays.add(newMarker)
                newMarker
            }
            
            marker.position = point
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            if (msg.equipment != null) {
                marker.title = "EQUIPMENT: ${msg.equipment}\nFrom: ${msg.senderId.takeLast(6)}"
                marker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_save)
            } else {
                marker.title = "SOS: ${msg.content}\nFrom: ${msg.senderId.takeLast(6)}"
                marker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_myplaces)
            }
        }
        
        currentMap.invalidate()
    }

    private fun centerMapOnCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val userPoint = GeoPoint(location.latitude, location.longitude)
                    map?.controller?.animateTo(userPoint)
                }
            }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val listPermissionsNeeded = ArrayList<String>()
        for (p in permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p)
            }
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
        locationOverlay?.disableMyLocation()
    }
}
