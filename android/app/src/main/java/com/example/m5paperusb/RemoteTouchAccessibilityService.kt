package com.example.m5paperusb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
            val service = instance ?: run {
                Log.w(TAG, "Cannot perform scroll: RemoteTouchAccessibilityService not running/enabled")
                return false
            }
            val metrics = service.resources.displayMetrics
            val width = if (ScreenCaptureService.screenWidth > 0) ScreenCaptureService.screenWidth.toFloat() else metrics.widthPixels.toFloat()
            val height = if (ScreenCaptureService.screenHeight > 0) ScreenCaptureService.screenHeight.toFloat() else metrics.heightPixels.toFloat()
            val midX = width * 0.5f
            val startY = height * 0.35f
            val endY = height * 0.75f
            return injectSwipe(midX, startY, midX, endY, 250, onComplete)
        }

        fun performScrollDown(onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot perform scroll: RemoteTouchAccessibilityService not running/enabled")
                return false
            }
            val metrics = service.resources.displayMetrics
            val width = if (ScreenCaptureService.screenWidth > 0) ScreenCaptureService.screenWidth.toFloat() else metrics.widthPixels.toFloat()
            val height = if (ScreenCaptureService.screenHeight > 0) ScreenCaptureService.screenHeight.toFloat() else metrics.heightPixels.toFloat()
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

        fun performHome(onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot perform Home: RemoteTouchAccessibilityService not running/enabled")
                return false
            }
            val ok = service.performGlobalAction(GLOBAL_ACTION_HOME)
            if (ok) {
                onComplete?.invoke()
            }
            return ok
        }

        private fun findFocusedOrEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocused != null) return inputFocused

            fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isFocused && node.isEditable) return node
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val res = search(child)
                    if (res != null) return res
                    child.recycle()
                }
                return null
            }
            val focusedEditable = search(root)
            if (focusedEditable != null) return focusedEditable

            fun searchAnyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEditable) return node
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val res = searchAnyEditable(child)
                    if (res != null) return res
                    child.recycle()
                }
                return null
            }
            return searchAnyEditable(root)
        }

        fun sendText(context: Context, text: String, onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot send text: RemoteTouchAccessibilityService not running/enabled")
                return false
            }
            val root = service.rootInActiveWindow
            val target = findFocusedOrEditableNode(root)
            var success = false
            if (target != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                target.recycle()
            }
            root?.recycle()

            // In ogni caso copiamo il testo negli appunti di sistema
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("m5paper_input", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.w(TAG, "Errore copia negli appunti: ${e.message}")
            }

            if (success) {
                onComplete?.invoke()
            }
            return success
        }

        fun appendText(char: String, onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val target = findFocusedOrEditableNode(root) ?: run {
                root.recycle()
                return false
            }
            val current = target.text?.toString() ?: ""
            val updated = current + char
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            root.recycle()
            if (ok) {
                onComplete?.invoke()
            }
            return ok
        }

        fun deleteLastChar(onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val target = findFocusedOrEditableNode(root) ?: run {
                root.recycle()
                return false
            }
            val current = target.text?.toString() ?: ""
            var ok = false
            if (current.isNotEmpty()) {
                val updated = current.dropLast(1)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
                }
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            target.recycle()
            root.recycle()
            if (ok) {
                onComplete?.invoke()
            }
            return ok
        }

        fun performEnter(onComplete: (() -> Unit)? = null): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow
            val target = findFocusedOrEditableNode(root)
            var ok = false
            if (target != null) {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target.recycle()
            }
            root?.recycle()
            if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = service.performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            }
            onComplete?.invoke()
            return ok
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
