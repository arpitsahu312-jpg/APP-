package com.example.OCEN 

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.OCEN.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : AppCompatActivity() {

    private var map: MapView? = null // Make it nullable to be safe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SAFETY NET: If this crashes, the app won't close. It will show a Toast.
        try {
            // 1. Initialize Map Settings (MUST be first)
            val ctx = applicationContext
            Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue = packageName

            setContentView(R.layout.activity_main)

            // 2. Setup the Map
            map = findViewById(R.id.map)
            
            if (map != null) {
                map!!.setTileSource(TileSourceFactory.MAPNIK)
                map!!.setMultiTouchControls(true)
                map!!.controller.setZoom(15.0)
                map!!.controller.setCenter(GeoPoint(28.7041, 77.1025)) // Delhi Coordinates
            } else {
                Toast.makeText(this, "CRITICAL ERROR: Map View not found! Check XML ID.", Toast.LENGTH_LONG).show()
            }

            // 3. Setup the Download Button
            val downloadBtn = findViewById<Button>(R.id.btnDownload)
            if (downloadBtn != null) {
                downloadBtn.setOnClickListener {
                    Toast.makeText(this, "Download Feature Clicked", Toast.LENGTH_SHORT).show()
                    // functionality removed for now to stop crash
                }
            }

            // 4. Check Permissions (The #1 Cause of Crashes)
            checkPermissions()

        } catch (e: Exception) {
            // THIS CATCHES THE CRASH
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION
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
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
    }
}