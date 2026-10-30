package com.example.m5paperusb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.widget.Toast
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
        const val MIN_SIDEBAR_WIDTH = 90f // Larghezza minima riservata a sinistra per i pulsanti funzione
        const val KEYBOARD_HEIGHT_X = 220f // Altezza riservata alla tastiera su schermo nella parte bassa (X da 0 a 220)
    }

    private val _transferState = MutableStateFlow<ScreenshotTransferState>(ScreenshotTransferState.Idle)
    val transferState: StateFlow<ScreenshotTransferState> = _transferState.asStateFlow()

    private val captureMutex = Mutex()
    private val hasPendingCapture = AtomicBoolean(false)

    var lastSidebarWidth: Float = 108f
        private set

    var isKeyboardVisible: Boolean = false
    var keyboardTextBuffer: String = ""
    var isSymbolsMode: Boolean = false
    var isShiftActive: Boolean = false

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

        val formattedBitmap = prepareBitmapForM5Paper(rawBitmap)
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

    /**
     * Calcola l'area del pulsante Home nella barra delle funzioni a sinistra (in vista utente).
     */
    fun getHomeButtonBounds(): RectF {
        val sidebarW = if (lastSidebarWidth >= MIN_SIDEBAR_WIDTH) lastSidebarWidth else MIN_SIDEBAR_WIDTH
        val btnSize = minOf(68f, sidebarW - 14f)
        val btnCenterY = sidebarW / 2f
        val btnMinY = btnCenterY - btnSize / 2f
        val btnMaxY = btnCenterY + btnSize / 2f

        // Slot 0 (Pulsante Home): posizionato in alto nella vista dell'utente
        val userTopY = 28f
        val btnMinX = TARGET_WIDTH - (userTopY + btnSize)
        val btnMaxX = TARGET_WIDTH - userTopY
        return RectF(btnMinX, btnMinY, btnMaxX, btnMaxY)
    }

    /**
     * Calcola l'area del pulsante Refresh / Cattura Schermo posizionato sotto Home (in vista utente).
     */
    fun getRefreshButtonBounds(): RectF {
        val sidebarW = if (lastSidebarWidth >= MIN_SIDEBAR_WIDTH) lastSidebarWidth else MIN_SIDEBAR_WIDTH
        val btnSize = minOf(68f, sidebarW - 14f)
        val btnCenterY = sidebarW / 2f
        val btnMinY = btnCenterY - btnSize / 2f
        val btnMaxY = btnCenterY + btnSize / 2f

        // Slot 1 (Pulsante Refresh): subito sotto il tasto Home
        val userTopY = 28f + btnSize + 16f
        val btnMinX = TARGET_WIDTH - (userTopY + btnSize)
        val btnMaxX = TARGET_WIDTH - userTopY
        return RectF(btnMinX, btnMinY, btnMaxX, btnMaxY)
    }

    /**
     * Calcola l'area del terzo pulsante (Tastiera su Schermo) posizionato sotto Refresh (in vista utente).
     */
    fun getKeyboardButtonBounds(): RectF {
        val sidebarW = if (lastSidebarWidth >= MIN_SIDEBAR_WIDTH) lastSidebarWidth else MIN_SIDEBAR_WIDTH
        val btnSize = minOf(68f, sidebarW - 14f)
        val btnCenterY = sidebarW / 2f
        val btnMinY = btnCenterY - btnSize / 2f
        val btnMaxY = btnCenterY + btnSize / 2f

        // Slot 2 (Pulsante Tastiera): subito sotto il tasto Refresh
        val userTopY = 28f + (btnSize + 16f) * 2
        val btnMinX = TARGET_WIDTH - (userTopY + btnSize)
        val btnMaxX = TARGET_WIDTH - userTopY
        return RectF(btnMinX, btnMinY, btnMaxX, btnMaxY)
    }

    /**
     * Verifica se le coordinate touch su M5Paper ricadono sul pulsante Home.
     */
    fun isHomeButtonTouch(touchX: Int, touchY: Int): Boolean {
        if (touchY.toFloat() > lastSidebarWidth + 12f) return false
        val homeBtn = getHomeButtonBounds()
        val refreshBtn = getRefreshButtonBounds()
        val splitX = (homeBtn.left + refreshBtn.right) / 2f
        return touchX.toFloat() >= splitX && touchX.toFloat() <= (homeBtn.right + 20f)
    }

    /**
     * Verifica se le coordinate touch su M5Paper ricadono sul pulsante Refresh.
     */
    fun isRefreshButtonTouch(touchX: Int, touchY: Int): Boolean {
        if (touchY.toFloat() > lastSidebarWidth + 12f) return false
        val homeBtn = getHomeButtonBounds()
        val refreshBtn = getRefreshButtonBounds()
        val kbBtn = getKeyboardButtonBounds()
        val splitTop = (homeBtn.left + refreshBtn.right) / 2f
        val splitBottom = (refreshBtn.left + kbBtn.right) / 2f
        return touchX.toFloat() >= splitBottom && touchX.toFloat() < splitTop
    }

    /**
     * Verifica se le coordinate touch su M5Paper ricadono sul pulsante Tastiera.
     */
    fun isKeyboardButtonTouch(touchX: Int, touchY: Int): Boolean {
        if (touchY.toFloat() > lastSidebarWidth + 12f) return false
        val refreshBtn = getRefreshButtonBounds()
        val kbBtn = getKeyboardButtonBounds()
        val splitTop = (refreshBtn.left + kbBtn.right) / 2f
        return touchX.toFloat() < splitTop && touchX.toFloat() >= (kbBtn.left - 20f)
    }

    /**
     * Verifica se le coordinate touch su M5Paper ricadono nell'area della tastiera a schermo.
     */
    fun isKeyboardTouch(touchX: Int, touchY: Int): Boolean {
        if (!isKeyboardVisible) return false
        if (touchX.toFloat() < 0f || touchX.toFloat() > KEYBOARD_HEIGHT_X) return false
        val sidebarW = if (lastSidebarWidth >= MIN_SIDEBAR_WIDTH) lastSidebarWidth else MIN_SIDEBAR_WIDTH
        return touchY.toFloat() >= (sidebarW - 6f) && touchY.toFloat() <= TARGET_HEIGHT.toFloat()
    }

    private fun prepareBitmapForM5Paper(source: Bitmap): Bitmap {
        // Ruota sempre lo screenshot di 90° in senso orario per visualizzazione ottimizzata su M5Paper
        val matrix = Matrix().apply { postRotate(90f) }
        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        val output = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE) // Sfondo bianco per display E-ink

        // Riserva spazio a sinistra per la barra dei pulsanti funzione (in vista utente)
        // La dimensione dello screenshot rimane SEMPRE piena (non si restringe all'apertura della tastiera)
        val maxScreenshotH = TARGET_HEIGHT - MIN_SIDEBAR_WIDTH
        val scale = minOf(
            TARGET_WIDTH.toFloat() / transformed.width,
            maxScreenshotH / transformed.height
        )
        val scaledW = (transformed.width * scale).toInt()
        val scaledH = (transformed.height * scale).toInt()
        val posX = (TARGET_WIDTH - scaledW).toFloat()
        val posY = (TARGET_HEIGHT - scaledH).toFloat()
        lastSidebarWidth = posY

        // 1. Disegna lo screenshot dello smartphone a dimensione normale
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val destRect = RectF(posX, posY, posX + scaledW, posY + scaledH)
        canvas.drawBitmap(transformed, null, destRect, paint)

        if (transformed !== source) {
            transformed.recycle()
        }

        // 2. Disegna la barra delle funzioni e i pulsanti a sinistra dello screenshot
        drawSidebar(canvas, posY)

        // 3. Se la tastiera è attiva, disegnala in sovrimpressione in basso (X da 0 a KEYBOARD_HEIGHT_X)
        if (isKeyboardVisible) {
            drawOnScreenKeyboard(canvas, posY)
        }

        return output
    }

    private fun drawSidebar(canvas: Canvas, sidebarWidth: Float) {
        // Linea verticale divisoria tra barra funzioni e screenshot
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(170, 170, 170)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawLine(0f, sidebarWidth, TARGET_WIDTH.toFloat(), sidebarWidth, dividerPaint)

        // 1. Disegna il pulsante HOME
        val homeBounds = getHomeButtonBounds()
        drawHomeButton(canvas, homeBounds)

        // 2. Disegna il pulsante REFRESH (Ricarica Schermo)
        val refreshBounds = getRefreshButtonBounds()
        drawRefreshButton(canvas, refreshBounds)

        // 3. Disegna il terzo pulsante TASTIERA (Attiva/Disattiva Tastiera)
        val kbBounds = getKeyboardButtonBounds()
        drawKeyboardButton(canvas, kbBounds, isKeyboardVisible)
    }

    private fun drawHomeButton(canvas: Canvas, bounds: RectF) {
        // Sfondo pulsante (grigio chiaro ad alto contrasto per E-ink)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 238, 238)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, 14f, 14f, bgPaint)

        // Bordo scuro del pulsante
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(bounds, 14f, 14f, borderPaint)

        // Disegna il simbolo Home (casetta) orientato verso l'alto nella visuale dell'utente (Canvas +X)
        val cX = bounds.centerX()
        val cY = bounds.centerY()

        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        // Tetto della casetta (vertice verso Canvas +X / Alto utente)
        val roofPath = Path().apply {
            moveTo(cX + 2f, cY - 20f)
            lineTo(cX + 20f, cY)
            lineTo(cX + 2f, cY + 20f)
        }
        canvas.drawPath(roofPath, symbolPaint)

        // Camino sul tetto
        val chimneyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRect(RectF(cX + 8f, cY + 8f, cX + 16f, cY + 14f), chimneyPaint)

        // Mura della casa (corpo inferiore, Canvas -X)
        val bodyPath = Path().apply {
            moveTo(cX + 2f, cY - 14f)
            lineTo(cX - 16f, cY - 14f)
            lineTo(cX - 16f, cY + 14f)
            lineTo(cX + 2f, cY + 14f)
        }
        canvas.drawPath(bodyPath, symbolPaint)

        // Porta della casa (piena nera)
        val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawRect(RectF(cX - 16f, cY - 5f, cX - 4f, cY + 5f), doorPaint)
    }

    private fun drawRefreshButton(canvas: Canvas, bounds: RectF) {
        // Sfondo pulsante (grigio chiaro ad alto contrasto per E-ink)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 238, 238)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, 14f, 14f, bgPaint)

        // Bordo scuro del pulsante
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(bounds, 14f, 14f, borderPaint)

        // Disegna il simbolo Refresh/Ricarica Schermo
        val cX = bounds.centerX()
        val cY = bounds.centerY()

        canvas.save()
        // Ruota di 90° per allineare l'orientamento grafico alla prospettiva dell'utente
        canvas.rotate(90f, cX, cY)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        val radius = 17f
        val arcRect = RectF(cX - radius, cY - radius, cX + radius, cY + radius)
        // Arco circolare orario con apertura in alto
        canvas.drawArc(arcRect, 330f, 280f, false, strokePaint)

        // Freccia all'estremità dell'arco circolare (a 250°)
        val endAngleRad = Math.toRadians(250.0)
        val tipX = cX + radius * Math.cos(endAngleRad).toFloat()
        val tipY = cY + radius * Math.sin(endAngleRad).toFloat()

        // Direzione tangenziale (in senso orario, verso destra/alto)
        val dirAngleRad = Math.toRadians(340.0)
        val dirX = Math.cos(dirAngleRad).toFloat()
        val dirY = Math.sin(dirAngleRad).toFloat()
        val normX = -dirY
        val normY = dirX

        val arrowPath = Path().apply {
            moveTo(tipX + dirX * 4.5f, tipY + dirY * 4.5f)
            lineTo(tipX - dirX * 7.5f + normX * 6.5f, tipY - dirY * 7.5f + normY * 6.5f)
            lineTo(tipX - dirX * 7.5f - normX * 6.5f, tipY - dirY * 7.5f - normY * 6.5f)
            close()
        }
        canvas.drawPath(arrowPath, fillPaint)

        canvas.restore()
    }

    private fun drawKeyboardButton(canvas: Canvas, bounds: RectF, isActive: Boolean) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isActive) Color.rgb(35, 35, 35) else Color.rgb(238, 238, 238)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, 14f, 14f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isActive) Color.BLACK else Color.rgb(30, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(bounds, 14f, 14f, borderPaint)

        val cX = bounds.centerX()
        val cY = bounds.centerY()

        canvas.save()
        // Ruota di 90° per orientamento utente
        canvas.rotate(90f, cX, cY)

        val strokeColor = if (isActive) Color.WHITE else Color.BLACK
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeJoin = Paint.Join.ROUND
        }

        // Bordo corpo tastiera
        val chassisRect = RectF(cX - 19f, cY - 13f, cX + 19f, cY + 13f)
        canvas.drawRoundRect(chassisRect, 4f, 4f, framePaint)

        val fillKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.FILL
        }

        // Tasti prima riga
        canvas.drawRect(RectF(cX - 14f, cY - 9f, cX - 8f, cY - 5f), fillKeyPaint)
        canvas.drawRect(RectF(cX - 3f, cY - 9f, cX + 3f, cY - 5f), fillKeyPaint)
        canvas.drawRect(RectF(cX + 8f, cY - 9f, cX + 14f, cY - 5f), fillKeyPaint)

        // Tasti seconda riga
        canvas.drawRect(RectF(cX - 14f, cY - 2f, cX - 8f, cY + 2f), fillKeyPaint)
        canvas.drawRect(RectF(cX - 3f, cY - 2f, cX + 3f, cY + 2f), fillKeyPaint)
        canvas.drawRect(RectF(cX + 8f, cY - 2f, cX + 14f, cY + 2f), fillKeyPaint)

        // Barra spaziatrice
        canvas.drawRect(RectF(cX - 11f, cY + 5f, cX + 11f, cY + 9f), fillKeyPaint)

        canvas.restore()
    }

    data class VirtualKey(
        val id: String,
        val label: String,
        val userLeft: Float,
        val userTop: Float,
        val userRight: Float,
        val userBottom: Float,
        val isActionKey: Boolean = false
    )

    fun getKeyboardKeys(kbWidth: Float): List<VirtualKey> {
        val keys = mutableListOf<VirtualKey>()
        val margin = 4f
        val spacing = 3f

        // Row 0: Cifre 1..0 (10 tasti sempre visibili)
        val row0Labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val keyW = (kbWidth - 2 * margin - (row0Labels.size - 1) * spacing) / row0Labels.size
        for (i in row0Labels.indices) {
            val left = margin + i * (keyW + spacing)
            keys.add(
                VirtualKey(
                    id = row0Labels[i],
                    label = row0Labels[i],
                    userLeft = left,
                    userTop = 8f,
                    userRight = left + keyW,
                    userBottom = 46f
                )
            )
        }

        // Row 1: Lettere Q..P (10 tasti)
        val row1Labels = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        for (i in row1Labels.indices) {
            val left = margin + i * (keyW + spacing)
            keys.add(
                VirtualKey(
                    id = row1Labels[i],
                    label = row1Labels[i],
                    userLeft = left,
                    userTop = 50f,
                    userRight = left + keyW,
                    userBottom = 88f
                )
            )
        }

        // Row 2: Lettere A..L (9 tasti)
        val row2Labels = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        val row2W = row2Labels.size * keyW + (row2Labels.size - 1) * spacing
        val indent2 = (kbWidth - row2W) / 2f
        for (i in row2Labels.indices) {
            val left = indent2 + i * (keyW + spacing)
            keys.add(
                VirtualKey(
                    id = row2Labels[i],
                    label = row2Labels[i],
                    userLeft = left,
                    userTop = 92f,
                    userRight = left + keyW,
                    userBottom = 130f
                )
            )
        }

        // Row 3: Shift (⇧) + Z..M (7 tasti) + Backspace (⌫)
        val row3Letters = listOf("Z", "X", "C", "V", "B", "N", "M")
        val shiftW = 46f
        val backspaceW = 54f
        val row3W = shiftW + row3Letters.size * keyW + backspaceW + (row3Letters.size + 1) * spacing
        val indent3 = (kbWidth - row3W) / 2f

        keys.add(
            VirtualKey(
                id = "SHIFT",
                label = "⇧",
                userLeft = indent3,
                userTop = 134f,
                userRight = indent3 + shiftW,
                userBottom = 172f
            )
        )

        var curLeft3 = indent3 + shiftW + spacing
        for (i in row3Letters.indices) {
            keys.add(
                VirtualKey(
                    id = row3Letters[i],
                    label = row3Letters[i],
                    userLeft = curLeft3,
                    userTop = 134f,
                    userRight = curLeft3 + keyW,
                    userBottom = 172f
                )
            )
            curLeft3 += keyW + spacing
        }

        keys.add(
            VirtualKey(
                id = "BACKSPACE",
                label = "⌫",
                userLeft = curLeft3,
                userTop = 134f,
                userRight = curLeft3 + backspaceW,
                userBottom = 172f
            )
        )

        // Row 4: Comma + Period + Spacebar + At/Hyphen + Send
        val commaW = 38f
        val dotW = 38f
        val atW = 38f
        val sendW = 98f
        val spacing4 = 4f
        val spaceW = kbWidth - 2 * margin - (commaW + dotW + atW + sendW) - 4 * spacing4
        var curLeft4 = margin

        keys.add(
            VirtualKey(
                id = ",",
                label = ",",
                userLeft = curLeft4,
                userTop = 176f,
                userRight = curLeft4 + commaW,
                userBottom = 214f
            )
        )
        curLeft4 += commaW + spacing4

        keys.add(
            VirtualKey(
                id = ".",
                label = ".",
                userLeft = curLeft4,
                userTop = 176f,
                userRight = curLeft4 + dotW,
                userBottom = 214f
            )
        )
        curLeft4 += dotW + spacing4

        keys.add(
            VirtualKey(
                id = "SPACE",
                label = "SPAZIO",
                userLeft = curLeft4,
                userTop = 176f,
                userRight = curLeft4 + spaceW,
                userBottom = 214f
            )
        )
        curLeft4 += spaceW + spacing4

        keys.add(
            VirtualKey(
                id = "@",
                label = "@",
                userLeft = curLeft4,
                userTop = 176f,
                userRight = curLeft4 + atW,
                userBottom = 214f
            )
        )
        curLeft4 += atW + spacing4

        keys.add(
            VirtualKey(
                id = "SEND",
                label = "INVIA ↵",
                userLeft = curLeft4,
                userTop = 176f,
                userRight = curLeft4 + sendW,
                userBottom = 214f,
                isActionKey = true
            )
        )

        return keys
    }

    private fun drawOnScreenKeyboard(canvas: Canvas, sidebarWidth: Float) {
        val kbWidth = TARGET_HEIGHT.toFloat() - sidebarWidth

        // Sfondo del contenitore tastiera
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 235, 235)
            style = Paint.Style.FILL
        }
        canvas.drawRect(RectF(0f, sidebarWidth, KEYBOARD_HEIGHT_X, TARGET_HEIGHT.toFloat()), bgPaint)

        // Linea divisoria superiore tra tastiera e schermata smartphone
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 100, 100)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawLine(KEYBOARD_HEIGHT_X, sidebarWidth, KEYBOARD_HEIGHT_X, TARGET_HEIGHT.toFloat(), dividerPaint)

        // Disegno tasti
        val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 50, 50)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val keyBgNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        val keyBgAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            style = Paint.Style.FILL
        }

        val keyBgActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 90)
            style = Paint.Style.FILL
        }

        val textPaintNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val textPaintAction = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val keys = getKeyboardKeys(kbWidth)
        for (key in keys) {
            val canvasMinY = sidebarWidth + key.userLeft
            val canvasMaxY = sidebarWidth + key.userRight
            val canvasMinX = KEYBOARD_HEIGHT_X - key.userBottom
            val canvasMaxX = KEYBOARD_HEIGHT_X - key.userTop
            val keyRect = RectF(canvasMinX, canvasMinY, canvasMaxX, canvasMaxY)

            val isHighlighted = (key.id == "SHIFT" && isShiftActive)
            val bg = if (key.isActionKey) {
                keyBgAction
            } else if (isHighlighted) {
                keyBgActive
            } else {
                keyBgNormal
            }

            canvas.drawRoundRect(keyRect, 6f, 6f, bg)
            canvas.drawRoundRect(keyRect, 6f, 6f, keyBorderPaint)

            val cX = keyRect.centerX()
            val cY = keyRect.centerY()
            canvas.save()
            canvas.rotate(90f, cX, cY)

            val tp = if (key.isActionKey || isHighlighted) textPaintAction else textPaintNormal
            if (key.label.length > 3) {
                tp.textSize = 14f
            } else {
                tp.textSize = 18f
            }
            canvas.drawText(key.label, cX, cY + 6f, tp)
            canvas.restore()
        }
    }

    fun handleKeyboardTouch(
        touchX: Int,
        touchY: Int,
        context: Context,
        onSendTriggered: () -> Unit
    ) {
        val sidebarW = if (lastSidebarWidth >= MIN_SIDEBAR_WIDTH) lastSidebarWidth else MIN_SIDEBAR_WIDTH
        val kbWidth = TARGET_HEIGHT.toFloat() - sidebarW
        val userX = touchY.toFloat() - sidebarW
        val userY = KEYBOARD_HEIGHT_X - touchX.toFloat()

        val keys = getKeyboardKeys(kbWidth)
        val tappedKey = keys.firstOrNull { key ->
            userX >= key.userLeft && userX <= key.userRight &&
            userY >= key.userTop && userY <= key.userBottom
        } ?: return

        when (tappedKey.id) {
            "SHIFT" -> {
                isShiftActive = !isShiftActive
                Toast.makeText(context, if (isShiftActive) "Maiuscolo (Shift)" else "Minuscolo", Toast.LENGTH_SHORT).show()
                // NON aggiorniamo lo schermo durante la digitazione
            }
            "SPACE" -> {
                keyboardTextBuffer += " "
                RemoteTouchAccessibilityService.appendText(" ")
                Toast.makeText(context, "Spazio", Toast.LENGTH_SHORT).show()
                // NON aggiorniamo lo schermo durante la digitazione
            }
            "BACKSPACE" -> {
                if (keyboardTextBuffer.isNotEmpty()) {
                    keyboardTextBuffer = keyboardTextBuffer.dropLast(1)
                }
                RemoteTouchAccessibilityService.deleteLastChar()
                Toast.makeText(context, "⌫ Canc", Toast.LENGTH_SHORT).show()
                // NON aggiorniamo lo schermo durante la digitazione
            }
            "SEND" -> {
                val textToSend = keyboardTextBuffer
                if (textToSend.isNotEmpty()) {
                    RemoteTouchAccessibilityService.sendText(context, textToSend)
                    RemoteTouchAccessibilityService.performEnter()
                    keyboardTextBuffer = ""
                    Toast.makeText(context, "Inviato: $textToSend", Toast.LENGTH_SHORT).show()
                } else {
                    RemoteTouchAccessibilityService.performEnter()
                    Toast.makeText(context, "Invio (Enter)", Toast.LENGTH_SHORT).show()
                }
                // SOLO ORA aggiorniamo lo schermo!
                onSendTriggered()
            }
            else -> {
                val rawChar = tappedKey.id
                val char = if (isShiftActive) rawChar.uppercase() else rawChar.lowercase()
                keyboardTextBuffer += char
                RemoteTouchAccessibilityService.appendText(char)
                if (isShiftActive) {
                    isShiftActive = false
                }
                Toast.makeText(context, char, Toast.LENGTH_SHORT).show()
                // NON aggiorniamo lo schermo durante la digitazione
            }
        }
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
        if (phoneWidth <= 0 || phoneHeight <= 0) return null

        val transW = phoneHeight
        val transH = phoneWidth

        // Lo screenshot dello smartphone non si restringe mai: scala sempre su TARGET_WIDTH
        val maxScreenshotH = TARGET_HEIGHT - MIN_SIDEBAR_WIDTH
        val scale = minOf(
            TARGET_WIDTH.toFloat() / transW,
            maxScreenshotH / transH
        )
        val scaledW = transW * scale
        val scaledH = transH * scale
        val posX = TARGET_WIDTH - scaledW
        val posY = TARGET_HEIGHT - scaledH

        // Se la tastiera è aperta e il tocco cade nella sua area (X <= KEYBOARD_HEIGHT_X), non è un tocco sullo smartphone
        if (isKeyboardVisible && touchX.toFloat() <= KEYBOARD_HEIGHT_X) {
            return null
        }

        // Check if inside screenshot bounds (sidebar touches at touchY < posY return null)
        if (touchX < posX || touchX > (posX + scaledW) || touchY < posY || touchY > (posY + scaledH)) {
            return null
        }

        val normX = ((touchX - posX) / scaledW).coerceIn(0f, 1f)
        val normY = ((touchY - posY) / scaledH).coerceIn(0f, 1f)

        // Rimappatura inversa della rotazione 90° oraria:
        val phoneX = (normY * phoneWidth).coerceIn(0f, (phoneWidth - 1).toFloat())
        val phoneY = ((1.0f - normX) * phoneHeight).coerceIn(0f, (phoneHeight - 1).toFloat())
        return Pair(phoneX, phoneY)
    }
}
