package com.devnav.rec

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var markersContainer: LinearLayout
    private lateinit var addMarkerButton: Button
    private lateinit var positionSpinner: Spinner
    private lateinit var scanIntervalEditText: EditText
    private lateinit var announceInitialCheckBox: CheckBox
    private lateinit var statusText: TextView

    private var markerViews: MutableList<MarkerItemView> = mutableListOf()
    private var pendingConfig: MonitorConfig? = null
    private var receiverRegistered = false

    private val monitorStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MonitorContract.ACTION_STATUS) {
                statusText.text = intent.getStringExtra(MonitorContract.EXTRA_STATUS_TEXT)
                    ?: getString(R.string.status_ready)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        markersContainer = findViewById(R.id.markersContainer)
        addMarkerButton = findViewById(R.id.addMarkerButton)
        positionSpinner = findViewById(R.id.positionSpinner)
        scanIntervalEditText = findViewById(R.id.scanIntervalEditText)
        announceInitialCheckBox = findViewById(R.id.announceInitialCheckBox)
        statusText = findViewById(R.id.statusText)

        restoreConfiguration()
        addMarkerButton.setOnClickListener { addMarker() }

        findViewById<Button>(R.id.grantOverlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener { beginMonitoringFlow() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopMonitoring() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MonitorContract.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitorStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(monitorStatusReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (ScreenMonitorService.isMonitoring) {
            statusText.text = getString(R.string.status_running)
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(monitorStatusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun restoreConfiguration() {
        val savedMarkers = preferences.getString(KEY_MARKERS, null)
        val defaultPosition = preferences.getInt(KEY_POSITION, 0).coerceIn(0, 4)
        val defaultInterval = preferences.getLong(KEY_INTERVAL, 900L)
        val defaultAnnounce = preferences.getBoolean(KEY_ANNOUNCE_INITIAL, false)

        scanIntervalEditText.setText(defaultInterval.toString())
        announceInitialCheckBox.isChecked = defaultAnnounce
        positionSpinner.setSelection(defaultPosition)

        if (savedMarkers != null) {
            val config = MonitorConfigSerializer.fromJson(savedMarkers)
            if (config != null) {
                config.markers.forEach { marker ->
                    addMarker(marker)
                }
            }
        }

        // Ensure at least one marker exists
        if (markerViews.isEmpty()) {
            addMarker()
        }
    }

    private fun addMarker(existing: MarkerConfig? = null) {
        val inflater = LayoutInflater.from(this)
        val markerView = inflater.inflate(R.layout.marker_item, markersContainer, false)
            .let { MarkerItemView(it, markerViews.size) }

        markerViews.add(markerView)
        markersContainer.addView(markerView.root)

        if (existing != null) {
            markerView.setMarker(existing)
        }

        // Set position spinner for all markers (shared setting)
        positionSpinner.setSelection(preferences.getInt(KEY_POSITION, 0).coerceIn(0, 4))

        // Update positions for all markers
        updateAllMarkerPositions()

        // Request focus on new marker's text field
        if (existing == null) {
            markerView.markerEditText.requestFocus()
        }
    }

    private fun removeMarker(markerIndex: Int) {
        if (markerViews.size <= 1) {
            statusText.text = getString(R.string.error_no_markers)
            return
        }
        markerViews.removeAt(markerIndex)
        markersContainer.removeAllViews()
        // Rebuild marker list with updated indices
        markerViews.forEachIndexed { index, markerView ->
            markerView.index = index
            markerView.updateTitle()
            markersContainer.addView(markerView.root)
        }
    }

    private fun updateAllMarkerPositions() {
        val position = positionSpinner.selectedItemPosition.coerceIn(0, 4)
        val positionEnum = RelativePosition.values()[position]
        markerViews.forEach { it.setPosition(positionEnum) }
    }

    private fun beginMonitoringFlow() {
        val config = collectConfig() ?: return
        saveConfiguration(config)
        pendingConfig = config

        if (!Settings.canDrawOverlays(this)) {
            statusText.text = getString(R.string.status_overlay_needed)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (pendingConfig == null) return
        statusText.text = getString(R.string.status_asking_capture)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SCREEN_CAPTURE -> {
                val config = pendingConfig
                pendingConfig = null
                if (resultCode != RESULT_OK || data == null || config == null) {
                    statusText.text = getString(R.string.status_capture_denied)
                    return
                }

                val serviceIntent = Intent(this, ScreenMonitorService::class.java).apply {
                    action = MonitorContract.ACTION_START
                    putExtra(MonitorContract.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    putExtra(MonitorContract.EXTRA_PROJECTION_DATA, data)
                }
                MonitorContract.run { serviceIntent.putMonitorConfig(config) }
                startForegroundService(serviceIntent)
                statusText.text = getString(R.string.status_running)
            }

            REQUEST_TEMPLATE_IMAGE -> {
                if (resultCode != RESULT_OK) return
                val uri = data?.data ?: return
                val persistedFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, persistedFlags)
                }
                // Update the last marker's template URI
                if (markerViews.isNotEmpty()) {
                    markerViews.last().setTemplateUri(uri.toString())
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestScreenCapture()
    }

    private fun collectConfig(): MonitorConfig? {
        if (markerViews.isEmpty()) {
            statusText.text = getString(R.string.error_no_markers)
            return null
        }

        val markers = markerViews.map { it.collectMarker() }.filter { it != null }
        if (markers.isEmpty()) {
            statusText.text = getString(R.string.error_no_markers)
            return null
        }

        val interval = scanIntervalEditText.text?.toString()?.toLongOrNull()
            ?.coerceIn(200L, 5_000L) ?: 900L
        scanIntervalEditText.setText(interval.toString())

        val position = RelativePosition.values()[positionSpinner.selectedItemPosition.coerceIn(0, 4)]

        return MonitorConfig(
            markers = markers,
            scanIntervalMs = interval,
            announceInitialValue = announceInitialCheckBox.isChecked
        )
    }

    private fun saveConfiguration(config: MonitorConfig) {
        preferences.edit()
            .putString(KEY_MARKERS, MonitorConfigSerializer.toJson(config))
            .putInt(KEY_POSITION, config.markers.firstOrNull()?.relativePosition?.ordinal ?: 0)
            .putLong(KEY_INTERVAL, config.scanIntervalMs)
            .putBoolean(KEY_ANNOUNCE_INITIAL, config.announceInitialValue)
            .apply()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = getString(R.string.status_ready)
            return
        }
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(settingsIntent)
    }

    private fun stopMonitoring() {
        startService(Intent(this, ScreenMonitorService::class.java).apply {
            action = MonitorContract.ACTION_STOP
        })
        statusText.text = getString(R.string.status_stopped)
    }

    private inner class MarkerItemView(
        val root: View,
        var index: Int
    ) {
        private val markerTitle: TextView = root.findViewById(R.id.markerTitle)
        private val markerModeGroup: RadioGroup = root.findViewById(R.id.markerModeGroup)
        private val markerEditText: EditText = root.findViewById(R.id.markerEditText)
        private val textModeContainer: View = root.findViewById(R.id.textModeContainer)
        private val imageModeContainer: View = root.findViewById(R.id.imageModeContainer)
        private val imageUriText: TextView = root.findViewById(R.id.imageUriText)
        private val chooseImageButton: Button = root.findViewById(R.id.chooseImageButton)
        private val removeButton: ImageButton = root.findViewById(R.id.removeMarkerButton)

        private var templateUri: String? = null

        init {
            markerModeGroup.setOnCheckedChangeListener { _, _ ->
                updateModeControls()
            }
            chooseImageButton.setOnClickListener { chooseTemplateImage() }
            removeButton.setOnClickListener { removeMarker(index) }
            updateModeControls()
            updateTitle()
        }

        fun setMarker(config: MarkerConfig) {
            index = config.markerText.hashCode() // Just for identification
            templateUri = config.templateUri
            markerEditText.setText(config.markerText)
            markerModeGroup.check(
                if (config.markerMode == MarkerMode.IMAGE) R.id.imageMarkerMode else R.id.textMarkerMode
            )
            imageUriText.text = templateUri?.let { parseAndShortenUri(it) } ?: getString(R.string.no_image_selected)
            updateModeControls()
            updateTitle()
        }

        fun setPosition(position: RelativePosition) {
            // Position is now shared across all markers, but stored per-marker for config
            // This is handled by the shared spinner
        }

        fun setTemplateUri(uri: String) {
            templateUri = uri
            imageUriText.error = null
            imageUriText.text = parseAndShortenUri(uri) ?: getString(R.string.no_image_selected)
        }

        private fun updateModeControls() {
            val imageMode = markerModeGroup.checkedRadioButtonId == R.id.imageMarkerMode
            textModeContainer.visibility = if (imageMode) View.GONE else View.VISIBLE
            imageModeContainer.visibility = if (imageMode) View.VISIBLE else View.GONE
            markerEditText.error = null
        }

        private fun updateTitle() {
            markerTitle.text = getString(R.string.marker_item_title, index + 1)
        }

        private fun chooseTemplateImage() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            // Use current marker's image picker
            startActivityForResult(Intent.createChooser(intent, getString(R.string.image_picker_title)), REQUEST_TEMPLATE_IMAGE + index)
        }

        private fun collectMarker(): MarkerConfig? {
            val markerMode = if (markerModeGroup.checkedRadioButtonId == R.id.imageMarkerMode) MarkerMode.IMAGE else MarkerMode.TEXT
            val markerText = markerEditText.text?.toString().orEmpty().trim()

            if (markerMode == MarkerMode.TEXT && markerText.isEmpty()) {
                markerEditText.error = getString(R.string.error_marker_required)
                markerEditText.requestFocus()
                return null
            }
            if (markerMode == MarkerMode.IMAGE && templateUri.isNullOrBlank()) {
                imageUriText.error = getString(R.string.error_image_required)
                return null
            }

            val position = RelativePosition.values()[positionSpinner.selectedItemPosition.coerceIn(0, 4)]

            return MarkerConfig(
                markerText = markerText,
                markerMode = markerMode,
                templateUri = templateUri,
                relativePosition = position
            )
        }

        private fun parseAndShortenUri(uriString: String): String? {
            return try {
                Uri.parse(uriString).lastPathSegment?.take(72)
            } catch (e: Exception) {
                uriString.take(72)
            }
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 4001
        private const val REQUEST_NOTIFICATIONS = 4002
        private const val REQUEST_TEMPLATE_IMAGE = 4100
        private const val PREFERENCES_NAME = "monitor_preferences"
        private const val KEY_MARKERS = "markers"
        private const val KEY_POSITION = "position"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_ANNOUNCE_INITIAL = "announce_initial"
    }
}
