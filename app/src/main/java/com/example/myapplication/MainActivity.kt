package com.example.myapplication

import android.os.Bundle
import androidx.preference.PreferenceManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import com.example.myapplication.R

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(28.7041, 77.1025))

        val downloadBtn = findViewById<Button>(R.id.btnDownload) 
        downloadBtn.setOnClickListener {
            downloadCurrentMapArea()
        }
    }

    private fun downloadCurrentMapArea() {
        val boundingBox = map.boundingBox
        
        val cacheManager = CacheManager(map)

        Toast.makeText(this, "Downloading map... please wait", Toast.LENGTH_SHORT).show()

        val zoomMin = 12
        val zoomMax = 16

        cacheManager.downloadAreaAsync(this, boundingBox, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Map Downloaded! You can go offline now.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onTaskFailed(errors: Int) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed. Check internet.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                // Optional: You could update a progress bar here
            }

            override fun downloadStarted() {
                // Optional: Show a loading spinner
            }

            override fun setPossibleTilesInArea(total: Int) {
                // Optional: Warn user if 'total' is too huge (e.g. > 5000 tiles)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
