package com.devnav.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * User-started foreground service. It captures only after MediaProjection consent, keeps a visible
 * notification/overlay, performs OCR in memory, and never writes screen frames to storage.
 */
class ScreenMonitorService : Service() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var analysisExecutor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameInFlight = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    private var config: MonitorConfig? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var templateMatcher: TemplateMatcher? = null
    private var overlay: FloatingOverlay? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var muted = false
    private var monitoring = false
    private var lastFrameAt = 0L
    private var lastTransientStatusAt = 0L
    private var lastTransientStatus: String? = null
    private var changeGate = ValueChangeGate()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                stopMonitoring(getString(R.string.service_projection_ended), stopProjection = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        captureThread = HandlerThread("screen-capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        analysisExecutor = Executors.newSingleThreadExecutor()
        initialiseTextToSpeech()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MonitorContract.ACTION_START -> startMonitoring(intent)
            MonitorContract.ACTION_STOP -> stopMonitoring(getString(R.string.service_stopped))
            else -> stopMonitoring(getString(R.string.service_stopped))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring(intent: Intent) {
        if (monitoring) {
            dispatchStatus(getString(R.string.status_running), running = true)
            return
        }
        if (stopping.get()) return

        val requestedConfig = MonitorContract.configFrom(intent)
        val resultCode = intent.getIntExtra(MonitorContract.EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
        val projectionData = projectionDataFrom(intent)
        if (requestedConfig == null || resultCode == Int.MIN_VALUE || projectionData == null) {
            dispatchStatus(getString(R.string.service_capture_error), running = false)
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            dispatchStatus(getString(R.string.status_overlay_needed), running = false)
            stopSelf()
            return
        }

        config = requestedConfig
        try {
            // Android 10+ requires this service to enter the mediaProjection foreground type
            // before MediaProjection is obtained and its virtual display is created.
            startForegroundCompat(buildNotification())

            if (requestedConfig.markerMode == MarkerMode.IMAGE) {
                templateMatcher = requestedConfig.templateUri?.let(::loadTemplateMatcher)
                checkNotNull(templateMatcher) { getString(R.string.service_template_error) }
            }

            overlay = FloatingOverlay(
                context = this,
                onStop = { mainHandler.post { stopMonitoring(getString(R.string.service_stopped)) } },
                onMutedChanged = { isMuted -> muted = isMuted }
            ).also { it.show() }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, projectionData)
                ?: error(getString(R.string.service_capture_error))
            projection = activeProjection
            activeProjection.registerCallback(projectionCallback, captureHandler)

            createCapturePipeline(activeProjection)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            changeGate = ValueChangeGate(requiredConsecutiveReads = 2)
            monitoring = true
            isMonitoring = true
            dispatchStatus(getString(R.string.service_started), running = true)
        } catch (error: Exception) {
            Log.w(TAG, "Could not start screen monitor", error)
            val message = if (requestedConfig.markerMode == MarkerMode.IMAGE && templateMatcher == null) {
                getString(R.string.service_template_error)
            } else {
                getString(R.string.service_capture_error)
            }
            stopMonitoring(message)
        }
    }

    private fun createCapturePipeline(activeProjection: MediaProjection) {
        releaseCapturePipeline()
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_BUFFER_COUNT)
        reader.setOnImageAvailableListener({ availableReader -> onImageAvailable(availableReader) }, captureHandler)
        imageReader = reader
        virtualDisplay = activeProjection.createVirtualDisplay(
            "ScreenNumberWatcher",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val activeConfig = config
        val now = SystemClock.elapsedRealtime()
        if (!monitoring || activeConfig == null ||
            now - lastFrameAt < activeConfig.scanIntervalMs ||
            !frameInFlight.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastFrameAt = now

        val bitmap = try {
            image.toBitmap()
        } catch (error: Exception) {
            Log.w(TAG, "Could not convert captured frame", error)
            frameInFlight.set(false)
            image.close()
            return
        }
        image.close()

        analysisExecutor.execute {
            analyseFrame(bitmap, activeConfig)
        }
    }

    private fun analyseFrame(bitmap: Bitmap, activeConfig: MonitorConfig) {
        if (!monitoring) {
            bitmap.recycleSafely()
            frameInFlight.set(false)
            return
        }

        val imageAnchor = if (activeConfig.markerMode == MarkerMode.IMAGE) {
            templateMatcher?.find(bitmap)
        } else {
            null
        }
        if (activeConfig.markerMode == MarkerMode.IMAGE && imageAnchor == null) {
            mainHandler.post { handleNoDetection(anchorFound = false) }
            bitmap.recycleSafely()
            frameInFlight.set(false)
            return
        }

        val activeRecognizer = recognizer
        if (activeRecognizer == null) {
            bitmap.recycleSafely()
            frameInFlight.set(false)
            return
        }

        activeRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(analysisExecutor) { recognizedText ->
                if (!monitoring) return@addOnSuccessListener
                val tokens = recognizedText.toOcrTokens()
                val anchorFound: Boolean
                val detection = when (activeConfig.markerMode) {
                    MarkerMode.TEXT -> {
                        anchorFound = ScreenValueDetector.hasTextAnchor(tokens, activeConfig.markerText)
                        ScreenValueDetector.findByText(
                            tokens,
                            activeConfig.markerText,
                            activeConfig.relativePosition
                        )
                    }

                    MarkerMode.IMAGE -> {
                        anchorFound = imageAnchor != null
                        ScreenValueDetector.findNearAnchor(
                            tokens,
                            checkNotNull(imageAnchor),
                            activeConfig.relativePosition
                        )
                    }
                }
                mainHandler.post { handleDetection(detection, anchorFound) }
            }
            .addOnFailureListener(analysisExecutor) { error ->
                Log.w(TAG, "OCR failed for captured frame", error)
                mainHandler.post { reportTransient(getString(R.string.service_waiting)) }
            }
            .addOnCompleteListener(analysisExecutor) {
                bitmap.recycleSafely()
                frameInFlight.set(false)
            }
    }

    private fun handleDetection(detection: NumberDetection?, anchorFound: Boolean) {
        if (!monitoring) return
        if (detection == null) {
            handleNoDetection(anchorFound)
            return
        }

        overlay?.showValue(detection.value)
        val change = changeGate.offer(detection.value) ?: return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(change.current))
        dispatchStatus(getString(R.string.notification_value, NumberParser.toPersianDigits(change.current)), running = true)

        if (!muted && (!change.isInitial || config?.announceInitialValue == true)) {
            speak(change.current, change.isInitial)
        }
    }

    private fun handleNoDetection(anchorFound: Boolean) {
        if (!monitoring) return
        changeGate.miss()
        overlay?.showSearching()
        reportTransient(
            getString(
                if (anchorFound) R.string.service_number_not_found else R.string.service_anchor_not_found
            )
        )
    }

    private fun reportTransient(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastTransientStatus && now - lastTransientStatusAt < TRANSIENT_STATUS_INTERVAL_MS) return
        lastTransientStatus = message
        lastTransientStatusAt = now
        dispatchStatus(message, running = true)
    }

    private fun speak(value: String, initial: Boolean) {
        if (!textToSpeechReady) return
        val spokenValue = NumberParser.toPersianDigits(value)
        val phrase = getString(
            if (initial) R.string.speech_first else R.string.speech_changed,
            spokenValue
        )
        textToSpeech?.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "screen-number-${SystemClock.elapsedRealtime()}"
        )
    }

    private fun initialiseTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) listener@{ status ->
            if (status != TextToSpeech.SUCCESS) return@listener
            val engine = textToSpeech ?: return@listener
            val persianResult = engine.setLanguage(Locale("fa", "IR"))
            textToSpeechReady = persianResult != TextToSpeech.LANG_MISSING_DATA &&
                persianResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (!textToSpeechReady) {
                val fallback = engine.setLanguage(Locale.getDefault())
                textToSpeechReady = fallback != TextToSpeech.LANG_MISSING_DATA &&
                    fallback != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_name)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(value: String? = null): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenMonitorService::class.java).apply {
                action = MonitorContract.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = value?.let {
            getString(R.string.notification_value, NumberParser.toPersianDigits(it))
        } ?: getString(R.string.notification_text)

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stat_watch, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopMonitoring(statusMessage: String, stopProjection: Boolean = true) {
        if (!stopping.compareAndSet(false, true)) return
        monitoring = false
        isMonitoring = false
        frameInFlight.set(false)

        releaseCapturePipeline()
        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            runCatching { activeProjection.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { activeProjection.stop() }
        }

        recognizer?.close()
        recognizer = null
        templateMatcher = null
        analysisExecutor.shutdownNow()
        captureThread.quitSafely()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady = false

        mainHandler.post {
            overlay?.remove()
            overlay = null
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        dispatchStatus(statusMessage, running = false)
        stopSelf()
    }

    private fun releaseCapturePipeline() {
        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null
        virtualDisplay?.let { display -> runCatching { display.release() } }
        virtualDisplay = null
    }

    private fun dispatchStatus(message: String, running: Boolean) {
        sendBroadcast(
            Intent(MonitorContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(MonitorContract.EXTRA_STATUS_TEXT, message)
                .putExtra(MonitorContract.EXTRA_RUNNING, running)
        )
    }

    @Suppress("DEPRECATION")
    private fun projectionDataFrom(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(MonitorContract.EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(MonitorContract.EXTRA_PROJECTION_DATA)
        }

    private fun loadTemplateMatcher(uriString: String): TemplateMatcher? {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        return try {
            TemplateMatcher.create(bitmap)
        } finally {
            bitmap.recycleSafely()
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_TEMPLATE_DIMENSION &&
            height / (sample * 2) >= MAX_TEMPLATE_DIMENSION
        ) {
            sample *= 2
        }
        return sample
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotated display still produces readable frames on most devices. The next user-started
        // session gets new dimensions; no hidden re-consent or projection restart is attempted.
    }

    override fun onDestroy() {
        if (!stopping.get()) stopMonitoring(getString(R.string.service_stopped))
        super.onDestroy()
    }

    private fun Text.toOcrTokens(): List<OcrToken> {
        val tokens = ArrayList<OcrToken>()
        textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.boundingBox?.toBounds()?.let { tokens += OcrToken(line.text, it) }
                line.elements.forEach { element ->
                    element.boundingBox?.toBounds()?.let { tokens += OcrToken(element.text, it) }
                }
            }
        }
        return tokens
    }

    private fun android.graphics.Rect.toBounds(): Bounds = Bounds(left, top, right, bottom)

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = max(0, rowStride - pixelStride * width)
        val paddedWidth = width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return paddedBitmap

        return Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
            paddedBitmap.recycleSafely()
        }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    companion object {
        private const val TAG = "ScreenNumberWatcher"
        private const val NOTIFICATION_CHANNEL_ID = "screen_number_monitor"
        private const val NOTIFICATION_ID = 1201
        private const val IMAGE_BUFFER_COUNT = 2
        private const val TRANSIENT_STATUS_INTERVAL_MS = 2_500L
        private const val MAX_TEMPLATE_DIMENSION = 320

        @Volatile
        var isMonitoring: Boolean = false
            private set
    }
}
