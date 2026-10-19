package com.example.m5paperusb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService that performs remote tap gesture injection on Android's screen
 * when touch coordinates are received from M5Paper.
 */
class RemoteTouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteTouchService"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        @Volatile
        var instance: RemoteTouchAccessibilityService? = null
            private set

        fun isRunning(): Boolean = _isRunningFlow.value

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun injectTap(x: Float, y: Float, onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot inject tap: RemoteTouchAccessibilityService not running/enabled")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path().apply {
                    moveTo(x, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 40)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                return service.dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "Gesture tap completed at ($x, $y)")
                            onComplete?.invoke()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture tap cancelled at ($x, $y)")
                        }
                    },
                    null
                )
            }
            return false
        }

        fun injectSwipe(
            startX: Float, startY: Float,
            endX: Float, endY: Float,
            durationMs: Long = 300,
            onComplete: (() -> Unit)? = null
        ): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot inject swipe: RemoteTouchAccessibilityService not running/enabled")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                return service.dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "Gesture swipe completed from ($startX, $startY) to ($endX, $endY)")
                            onComplete?.invoke()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture swipe cancelled")
                        }
                    },
                    null
                )
            }
            return false
        }

        fun performVolumeUp(context: Context): Boolean {
            return try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_RAISE,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Errore Volume Su: ${e.message}")
                false
            }
        }

        fun performVolumeDown(context: Context): Boolean {
            return try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Errore Volume Giù: ${e.message}")
                false
            }
        }

        fun performScrollUp(onComplete: (() -> Unit)? = null): Boolean {
            val width = ScreenCaptureService.screenWidth.toFloat()
            val height = ScreenCaptureService.screenHeight.toFloat()
            val midX = width * 0.5f
            val startY = height * 0.35f
            val endY = height * 0.75f
            return injectSwipe(midX, startY, midX, endY, 250, onComplete)
        }

        fun performScrollDown(onComplete: (() -> Unit)? = null): Boolean {
            val width = ScreenCaptureService.screenWidth.toFloat()
            val height = ScreenCaptureService.screenHeight.toFloat()
            val midX = width * 0.5f
            val startY = height * 0.75f
            val endY = height * 0.35f
            return injectSwipe(midX, startY, midX, endY, 250, onComplete)
        }

        fun performDpadUp(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.performGlobalAction(GLOBAL_ACTION_DPAD_UP)
            } else {
                false
            }
        }

        fun performDpadDown(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.performGlobalAction(GLOBAL_ACTION_DPAD_DOWN)
            } else {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunningFlow.value = true
        Log.d(TAG, "RemoteTouchAccessibilityService connected and ready")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not needed for gesture injection
    }

    override fun onInterrupt() {
        Log.d(TAG, "RemoteTouchAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isRunningFlow.value = false
        Log.d(TAG, "RemoteTouchAccessibilityService destroyed")
    }
}
