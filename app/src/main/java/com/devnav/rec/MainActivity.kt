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
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var markerModeGroup: RadioGroup
    private lateinit var markerEditText: EditText
    private lateinit var textModeContainer: View
    private lateinit var imageModeContainer: View
    private lateinit var imageUriText: TextView
    private lateinit var positionSpinner: Spinner
    private lateinit var scanIntervalEditText: EditText
    private lateinit var announceInitialCheckBox: CheckBox
    private lateinit var statusText: TextView

    private var templateUri: String? = null
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

        markerModeGroup = findViewById(R.id.markerModeGroup)
        markerEditText = findViewById(R.id.markerEditText)
        textModeContainer = findViewById(R.id.textModeContainer)
        imageModeContainer = findViewById(R.id.imageModeContainer)
        imageUriText = findViewById(R.id.imageUriText)
        positionSpinner = findViewById(R.id.positionSpinner)
        scanIntervalEditText = findViewById(R.id.scanIntervalEditText)
        announceInitialCheckBox = findViewById(R.id.announceInitialCheckBox)
        statusText = findViewById(R.id.statusText)

        restoreConfiguration()
        markerModeGroup.setOnCheckedChangeListener { _, _ -> updateModeControls() }
        updateModeControls()

        findViewById<Button>(R.id.grantOverlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.chooseImageButton).setOnClickListener { chooseTemplateImage() }
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
        markerEditText.setText(preferences.getString(KEY_MARKER, ""))
        templateUri = preferences.getString(KEY_TEMPLATE_URI, null)
        scanIntervalEditText.setText(preferences.getLong(KEY_INTERVAL, 900L).toString())
        announceInitialCheckBox.isChecked = preferences.getBoolean(KEY_ANNOUNCE_INITIAL, false)
        positionSpinner.setSelection(preferences.getInt(KEY_POSITION, 0).coerceIn(0, 4))

        val mode = preferences.getString(KEY_MODE, MarkerMode.TEXT.name)
        markerModeGroup.check(
            if (mode == MarkerMode.IMAGE.name) R.id.imageMarkerMode else R.id.textMarkerMode
        )
        renderTemplateUri()
    }

    private fun updateModeControls() {
        val imageMode = selectedMarkerMode() == MarkerMode.IMAGE
        textModeContainer.visibility = if (imageMode) View.GONE else View.VISIBLE
        imageModeContainer.visibility = if (imageMode) View.VISIBLE else View.GONE
        markerEditText.error = null
    }

    private fun chooseTemplateImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(Intent.createChooser(intent, getString(R.string.image_picker_title)), REQUEST_TEMPLATE_IMAGE)
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
                templateUri = uri.toString()
                renderTemplateUri()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A foreground-service notification remains visible in Android's task manager even when
        // normal notification permission was declined, so capture can still proceed explicitly.
        if (requestCode == REQUEST_NOTIFICATIONS) requestScreenCapture()
    }

    private fun collectConfig(): MonitorConfig? {
        val markerMode = selectedMarkerMode()
        val marker = markerEditText.text?.toString().orEmpty().trim()
        if (markerMode == MarkerMode.TEXT && marker.isEmpty()) {
            markerEditText.error = getString(R.string.error_marker_required)
            markerEditText.requestFocus()
            return null
        }
        if (markerMode == MarkerMode.IMAGE && templateUri.isNullOrBlank()) {
            imageUriText.error = getString(R.string.error_image_required)
            return null
        }

        val interval = scanIntervalEditText.text?.toString()?.toLongOrNull()
            ?.coerceIn(500L, 5_000L) ?: 900L
        // Write back the clamped value so the actual scan rate is never surprising.
        scanIntervalEditText.setText(interval.toString())

        return MonitorConfig(
            markerMode = markerMode,
            markerText = marker,
            templateUri = templateUri,
            relativePosition = RelativePosition.values()[positionSpinner.selectedItemPosition.coerceIn(0, 4)],
            scanIntervalMs = interval,
            announceInitialValue = announceInitialCheckBox.isChecked
        )
    }

    private fun saveConfiguration(config: MonitorConfig) {
        preferences.edit()
            .putString(KEY_MODE, config.markerMode.name)
            .putString(KEY_MARKER, config.markerText)
            .putString(KEY_TEMPLATE_URI, config.templateUri)
            .putInt(KEY_POSITION, config.relativePosition.ordinal)
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

    private fun selectedMarkerMode(): MarkerMode =
        if (markerModeGroup.checkedRadioButtonId == R.id.imageMarkerMode) MarkerMode.IMAGE else MarkerMode.TEXT

    private fun renderTemplateUri() {
        imageUriText.error = null
        imageUriText.text = templateUri?.let { value ->
            Uri.parse(value).lastPathSegment?.take(72) ?: value.take(72)
        } ?: getString(R.string.no_image_selected)
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 4001
        private const val REQUEST_NOTIFICATIONS = 4002
        private const val REQUEST_TEMPLATE_IMAGE = 4003
        private const val PREFERENCES_NAME = "monitor_preferences"
        private const val KEY_MODE = "mode"
        private const val KEY_MARKER = "marker"
        private const val KEY_TEMPLATE_URI = "template_uri"
        private const val KEY_POSITION = "position"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_ANNOUNCE_INITIAL = "announce_initial"
    }
}
