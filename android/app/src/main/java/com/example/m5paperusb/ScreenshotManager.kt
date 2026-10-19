package com.example.m5paperusb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

enum class ScreenshotFitMode {
    ROTATE_90_FILL, // Ruota 90° e scala (riempie lo schermo a 960x432, ideale se si impugna M5Paper in verticale)
    FIT_CENTER      // Centrato orizzontale in 960x540 con sfondo bianco
}

sealed class ScreenshotTransferState {
    object Idle : ScreenshotTransferState()
    object Capturing : ScreenshotTransferState()
    data class Sending(val sentBytes: Int, val totalBytes: Int, val progress: Float) : ScreenshotTransferState()
    data class Success(val totalBytes: Int, val durationMs: Long) : ScreenshotTransferState()
    data class Error(val message: String) : ScreenshotTransferState()
}

class ScreenshotManager(private val usbController: UsbSerialController) {

    companion object {
        private const val TAG = "ScreenshotManager"
        const val TARGET_WIDTH = 960
        const val TARGET_HEIGHT = 540
        const val CHUNK_SIZE = 2048 // 2 KB chunk size fits inside ESP32 4KB RX buffer
    }

    private val _transferState = MutableStateFlow<ScreenshotTransferState>(ScreenshotTransferState.Idle)
    val transferState: StateFlow<ScreenshotTransferState> = _transferState.asStateFlow()

    private val captureMutex = Mutex()
    private val hasPendingCapture = AtomicBoolean(false)

    var fitMode: ScreenshotFitMode = ScreenshotFitMode.ROTATE_90_FILL
    var jpegQuality: Int = 80 // 80% gives sharp clarity and compact size (~40-70 KB)

    suspend fun captureAndSend(): Boolean {
        if (!captureMutex.tryLock()) {
            hasPendingCapture.set(true)
            Log.d(TAG, "Trasferimento screenshot già in corso: programmato refresh successivo.")
            return true
        }

        try {
            var success = doCaptureAndSend()
            while (hasPendingCapture.getAndSet(false)) {
                kotlinx.coroutines.delay(250)
                success = doCaptureAndSend()
            }
            return success
        } finally {
            captureMutex.unlock()
        }
    }

    private suspend fun doCaptureAndSend(): Boolean {
        if (usbController.connectionStatus.value !is ConnectionStatus.Connected) {
            _transferState.value = ScreenshotTransferState.Error("M5Paper non collegato via USB")
            return false
        }

        _transferState.value = ScreenshotTransferState.Capturing

        val startTime = System.currentTimeMillis()
        val rawBitmap = ScreenCaptureService.captureCurrentScreen() ?: run {
            _transferState.value = ScreenshotTransferState.Error("Cattura schermo non disponibile (autorizzazione non attiva)")
            return false
        }

        val formattedBitmap = prepareBitmapForM5Paper(rawBitmap, fitMode)
        rawBitmap.recycle()

        val jpegBytes = compressToJpeg(formattedBitmap, jpegQuality)
        formattedBitmap.recycle()

        Log.d(TAG, "Screenshot pronto: ${jpegBytes.size} bytes. Invio verso M5Paper...")

        // Setup completion listener synchronously before sending to avoid race condition with fast E-ink refresh
        val doneDeferred = CompletableDeferred<String>()
        val doneListener: (String) -> Unit = { line ->
            if (line == "ACK:IMG_DONE" || line.startsWith("ERR:")) {
                doneDeferred.complete(line)
            }
        }
        usbController.addLineListener(doneListener)

        try {
            // 1. Begin image transmission
            val beginCmd = "IMG_BEGIN:${jpegBytes.size}"
            val beginAck = usbController.sendCommandAndWaitResponse(beginCmd, "ACK:IMG_BEGIN", 3000)
            if (!beginAck) {
                _transferState.value = ScreenshotTransferState.Error("M5Paper non ha confermato IMG_BEGIN")
                usbController.sendLine("IMG_ABORT")
                return false
            }

            // 2. Send in chunks
            var offset = 0
            val totalBytes = jpegBytes.size

            while (offset < totalBytes) {
                val chunkLen = minOf(CHUNK_SIZE, totalBytes - offset)
                val chunk = jpegBytes.copyOfRange(offset, offset + chunkLen)

                _transferState.value = ScreenshotTransferState.Sending(
                    sentBytes = offset,
                    totalBytes = totalBytes,
                    progress = offset.toFloat() / totalBytes
                )

                val chunkAck = usbController.sendChunkAndWaitAck(chunk, 4000)
                if (!chunkAck) {
                    _transferState.value = ScreenshotTransferState.Error("Errore o timeout chunk a offset $offset")
                    usbController.sendLine("IMG_ABORT")
                    return false
                }

                offset += chunkLen
            }

            _transferState.value = ScreenshotTransferState.Sending(
                sentBytes = totalBytes,
                totalBytes = totalBytes,
                progress = 1.0f
            )

            // 3. Wait for ACK:IMG_DONE from M5Paper after E-ink rendering
            val doneResp = withTimeoutOrNull(8000) { doneDeferred.await() }
            val elapsed = System.currentTimeMillis() - startTime

            return if (doneResp == "ACK:IMG_DONE") {
                _transferState.value = ScreenshotTransferState.Success(totalBytes, elapsed)
                Log.d(TAG, "Screenshot inviato e visualizzato su M5Paper in ${elapsed}ms")
                true
            } else {
                val errMsg = doneResp ?: "Timeout attesa rendering display (ACK:IMG_DONE)"
                _transferState.value = ScreenshotTransferState.Error(errMsg)
                false
            }
        } finally {
            usbController.removeLineListener(doneListener)
        }
    }

    private fun prepareBitmapForM5Paper(source: Bitmap, mode: ScreenshotFitMode): Bitmap {
        val transformed = when (mode) {
            ScreenshotFitMode.ROTATE_90_FILL -> {
                // If phone is in portrait (width < height), rotate 90 degrees clockwise
                if (source.width < source.height) {
                    val matrix = Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                } else {
                    source
                }
            }
            ScreenshotFitMode.FIT_CENTER -> source
        }

        val output = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE) // White background for clean e-ink display

        val scale = minOf(
            TARGET_WIDTH.toFloat() / transformed.width,
            TARGET_HEIGHT.toFloat() / transformed.height
        )
        val scaledW = (transformed.width * scale).toInt()
        val scaledH = (transformed.height * scale).toInt()
        val posX = (TARGET_WIDTH - scaledW) / 2f
        val posY = (TARGET_HEIGHT - scaledH) / 2f

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val destRect = RectF(posX, posY, posX + scaledW, posY + scaledH)
        canvas.drawBitmap(transformed, null, destRect, paint)

        if (transformed !== source) {
            transformed.recycle()
        }

        return output
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Maps M5Paper touch coordinates (0..960, 0..540) back to the original smartphone screen coordinates.
     * Returns Pair(phoneX, phoneY), or null if the touch fell outside the scaled image bounds.
     */
    fun mapM5PaperTouchToPhone(
        touchX: Int,
        touchY: Int,
        phoneWidth: Int = ScreenCaptureService.screenWidth,
        phoneHeight: Int = ScreenCaptureService.screenHeight
    ): Pair<Float, Float>? {
        val (transW, transH, isRotated) = when (fitMode) {
            ScreenshotFitMode.ROTATE_90_FILL -> {
                if (phoneWidth < phoneHeight) {
                    Triple(phoneHeight, phoneWidth, true)
                } else {
                    Triple(phoneWidth, phoneHeight, false)
                }
            }
            ScreenshotFitMode.FIT_CENTER -> Triple(phoneWidth, phoneHeight, false)
        }

        val scale = minOf(
            TARGET_WIDTH.toFloat() / transW,
            TARGET_HEIGHT.toFloat() / transH
        )
        val scaledW = (transW * scale)
        val scaledH = (transH * scale)
        val posX = (TARGET_WIDTH - scaledW) / 2f
        val posY = (TARGET_HEIGHT - scaledH) / 2f

        // Check if inside bounds
        if (touchX < posX || touchX > (posX + scaledW) || touchY < posY || touchY > (posY + scaledH)) {
            return null
        }

        val normX = ((touchX - posX) / scaledW).coerceIn(0f, 1f)
        val normY = ((touchY - posY) / scaledH).coerceIn(0f, 1f)

        return if (isRotated) {
            val phoneX = (normY * phoneWidth).coerceIn(0f, (phoneWidth - 1).toFloat())
            val phoneY = ((1.0f - normX) * phoneHeight).coerceIn(0f, (phoneHeight - 1).toFloat())
            Pair(phoneX, phoneY)
        } else {
            val phoneX = (normX * phoneWidth).coerceIn(0f, (phoneWidth - 1).toFloat())
            val phoneY = (normY * phoneHeight).coerceIn(0f, (phoneHeight - 1).toFloat())
            Pair(phoneX, phoneY)
        }
    }
}
