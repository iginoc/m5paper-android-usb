package com.example.m5paperusb

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground Service that maintains the MediaProjection and VirtualDisplay session.
 * On Android 14 (API 34), MediaProjection strictly requires a Foreground Service with type mediaProjection.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "m5paper_screen_capture"

        const val ACTION_START = "com.example.m5paperusb.START_CAPTURE"
        const val ACTION_STOP = "com.example.m5paperusb.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private var instance: ScreenCaptureService? = null

        suspend fun captureCurrentScreen(): Bitmap? {
            return instance?.capture()
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var displayWidth = 1080
    private var displayHeight = 1920

    private var lastBitmap: Bitmap? = null
    private var pendingCapture: CompletableDeferred<Bitmap?>? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithNotification()
                    initMediaProjection(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid resultCode or resultData for MediaProjection")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "M5Paper Screenshot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Consente l'invio dello screenshot a M5Paper con il tasto centrale"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M5Paper Screen Mirroring Attivo")
            .setContentText("Premi il tasto centrale su M5Paper per acquisire lo schermo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

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

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }

        // Android 14 REQUIREMENT: Must register callback before creating VirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection stopped by system/user")
                stopCapture()
                stopSelf()
            }
        }, handler)

        mediaProjection = projection

        // Calculate screen dimensions
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        val width: Int
        val height: Int
        val density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            density = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }

        displayWidth = width
        displayHeight = height

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bmp = imageToBitmap(img, width, height)
                img.close()
                if (bmp != null) {
                    synchronized(this@ScreenCaptureService) {
                        lastBitmap?.recycle()
                        lastBitmap = bmp
                        pendingCapture?.complete(bmp)
                        pendingCapture = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onImageAvailable", e)
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "M5PaperScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        instance = this
        _isRunning.value = true
        Log.d(TAG, "VirtualDisplay created: ${width}x${height} @ ${density}dpi")
    }

    suspend fun capture(): Bitmap? {
        val reader = imageReader ?: return null

        // 1. Try to acquire latest image immediately if available
        try {
            val img = reader.acquireLatestImage()
            if (img != null) {
                val bmp = imageToBitmap(img, displayWidth, displayHeight)
                img.close()
                if (bmp != null) {
                    synchronized(this) {
                        lastBitmap?.recycle()
                        lastBitmap = bmp
                    }
                    return bmp.copy(bmp.config, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct acquire failed: ${e.message}")
        }

        // 2. If screen is static and we have a cached frame, return a copy
        synchronized(this) {
            lastBitmap?.let {
                if (!it.isRecycled) {
                    return it.copy(it.config, false)
                }
            }
        }

        // 3. Otherwise wait for next frame from ImageReader listener
        val deferred = CompletableDeferred<Bitmap?>()
        synchronized(this) {
            pendingCapture = deferred
        }
        val bmp = withTimeoutOrNull(1500) {
            deferred.await()
        }
        return bmp?.copy(bmp.config, false)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap conversion failed", e)
            null
        }
    }

    private fun stopCapture() {
        _isRunning.value = false
        instance = null

        try {
            virtualDisplay?.release()
        } catch (ignored: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (ignored: Exception) {}
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (ignored: Exception) {}
        mediaProjection = null

        synchronized(this) {
            lastBitmap?.recycle()
            lastBitmap = null
            pendingCapture?.cancel()
            pendingCapture = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
