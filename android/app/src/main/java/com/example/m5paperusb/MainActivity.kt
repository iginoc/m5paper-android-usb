package com.example.m5paperusb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.m5paperusb.ui.theme.M5PaperUsbCompanionTheme
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val timestamp: String,
    val direction: Direction,
    val text: String
)

enum class Direction {
    TX, RX, INFO, ERROR
}

enum class UpDownKeyAction(val label: String) {
    VOLUME("Volume Su/Giù"),
    SCROLL("Scorri Schermo"),
    DPAD("Frecce D-Pad")
}

data class DeviceTelemetry(
    val batteryMv: Int = 0,
    val batteryPct: Int = 0,
    val temp: Float = 0f,
    val hum: Float = 0f,
    val uptime: Long = 0,
    val lastTouch: String = "None"
)

class MainActivity : ComponentActivity() {

    private lateinit var usbController: UsbSerialController
    private lateinit var screenshotManager: ScreenshotManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbController = UsbSerialController(this)
        screenshotManager = ScreenshotManager(usbController)

        setContent {
            M5PaperUsbCompanionTheme {
                MainScreen(
                    usbController = usbController,
                    screenshotManager = screenshotManager,
                    onConnectClick = { baud -> usbController.scanAndConnect(baud) },
                    onDisconnectClick = { usbController.disconnect() },
                    onSend = { cmd -> usbController.sendLine(cmd) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbController.cleanUp()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbController: UsbSerialController,
    screenshotManager: ScreenshotManager,
    onConnectClick: (Int) -> Unit,
    onDisconnectClick: () -> Unit,
    onSend: (String) -> Boolean
) {
    val context = LocalContext.current
    val connectionStatus by usbController.connectionStatus.collectAsState()
    val isCaptureRunning by ScreenCaptureService.isRunning.collectAsState()
    val transferState by screenshotManager.transferState.collectAsState()
    val isRemoteTouchActive by RemoteTouchAccessibilityService.isRunningFlow.collectAsState()

    var isShowingScreenshot by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<LogEntry>() }
    var telemetry by remember { mutableStateOf(DeviceTelemetry()) }
    var inputText by remember { mutableStateOf("") }
    var selectedBaudRate by remember { mutableStateOf(UsbSerialController.DEFAULT_BAUD_RATE) }
    var selectedFitMode by remember { mutableStateOf(screenshotManager.fitMode) }
    var selectedKeyAction by remember { mutableStateOf(UpDownKeyAction.VOLUME) }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(transferState) {
        if (transferState is ScreenshotTransferState.Success) {
            isShowingScreenshot = true
        }
    }

    fun addLog(direction: Direction, text: String) {
        val entry = LogEntry(timeFormatter.format(Date()), direction, text)
        logs.add(entry)
        coroutineScope.launch {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }

    // MediaProjection permission launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            addLog(Direction.INFO, "Cattura schermo abilitata! Ora premi il tasto centrale su M5Paper.")
            Toast.makeText(context, "Cattura schermo abilitata!", Toast.LENGTH_SHORT).show()
        } else {
            addLog(Direction.ERROR, "Permesso cattura schermo negato")
            Toast.makeText(context, "Permesso non concesso", Toast.LENGTH_SHORT).show()
        }
    }

    // Function to trigger screenshot transmission
    fun triggerScreenshotCapture() {
        if (!isCaptureRunning) {
            addLog(Direction.ERROR, "Cattura schermo non attiva. Tocca 'Abilita Cattura Schermo'.")
            Toast.makeText(context, "Abilita prima la cattura schermo", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch {
            addLog(Direction.INFO, "Acquisizione screenshot e invio verso M5Paper...")
            val success = screenshotManager.captureAndSend()
            if (success) {
                addLog(Direction.INFO, "Screenshot inviato e visualizzato a schermo intero su M5Paper!")
            } else {
                val errMsg = (screenshotManager.transferState.value as? ScreenshotTransferState.Error)?.message ?: "Errore sconosciuto"
                addLog(Direction.ERROR, "Invio screenshot fallito: $errMsg")
            }
        }
    }

    // Handle Up/Down physical key from M5Paper
    fun handleUpDownKey(isUp: Boolean) {
        when (selectedKeyAction) {
            UpDownKeyAction.VOLUME -> {
                val ok = if (isUp) {
                    RemoteTouchAccessibilityService.performVolumeUp(context)
                } else {
                    RemoteTouchAccessibilityService.performVolumeDown(context)
                }
                if (ok) {
                    addLog(Direction.INFO, if (isUp) "🔊 Smartphone: Volume Su (+)" else "🔉 Smartphone: Volume Giù (-)")
                    if (isCaptureRunning && isShowingScreenshot) {
                        coroutineScope.launch {
                            delay(400)
                            triggerScreenshotCapture()
                        }
                    }
                } else {
                    addLog(Direction.ERROR, "Errore regolazione volume sullo smartphone")
                }
            }
            UpDownKeyAction.SCROLL -> {
                if (RemoteTouchAccessibilityService.isRunning()) {
                    val ok = if (isUp) {
                        RemoteTouchAccessibilityService.performScrollUp {
                            if (isCaptureRunning && isShowingScreenshot) {
                                coroutineScope.launch {
                                    delay(400)
                                    triggerScreenshotCapture()
                                }
                            }
                        }
                    } else {
                        RemoteTouchAccessibilityService.performScrollDown {
                            if (isCaptureRunning && isShowingScreenshot) {
                                coroutineScope.launch {
                                    delay(400)
                                    triggerScreenshotCapture()
                                }
                            }
                        }
                    }
                    if (ok) {
                        addLog(Direction.INFO, if (isUp) "📜 Smartphone: Scorri Su (▲)" else "📜 Smartphone: Scorri Giù (▼)")
                    } else {
                        addLog(Direction.ERROR, "Errore esecuzione scorrimento")
                    }
                } else {
                    addLog(Direction.ERROR, "Attiva il servizio Accessibilità per consentire lo scorrimento dello schermo")
                }
            }
            UpDownKeyAction.DPAD -> {
                if (RemoteTouchAccessibilityService.isRunning()) {
                    val ok = if (isUp) {
                        RemoteTouchAccessibilityService.performDpadUp()
                    } else {
                        RemoteTouchAccessibilityService.performDpadDown()
                    }
                    if (ok) {
                        addLog(Direction.INFO, if (isUp) "⬆️ Smartphone: Freccia Su (D-Pad)" else "⬇️ Smartphone: Freccia Giù (D-Pad)")
                        if (isCaptureRunning && isShowingScreenshot) {
                            coroutineScope.launch {
                                delay(350)
                                triggerScreenshotCapture()
                            }
                        }
                    } else {
                        // Fallback to scroll if D-Pad not supported on current Android version
                        if (isUp) {
                            RemoteTouchAccessibilityService.performScrollUp {
                                if (isCaptureRunning && isShowingScreenshot) {
                                    coroutineScope.launch {
                                        delay(400)
                                        triggerScreenshotCapture()
                                    }
                                }
                            }
                        } else {
                            RemoteTouchAccessibilityService.performScrollDown {
                                if (isCaptureRunning && isShowingScreenshot) {
                                    coroutineScope.launch {
                                        delay(400)
                                        triggerScreenshotCapture()
                                    }
                                }
                            }
                        }
                        addLog(Direction.INFO, if (isUp) "⬆️ Smartphone: Scorrimento Su (fallback)" else "⬇️ Smartphone: Scorrimento Giù (fallback)")
                    }
                } else {
                    addLog(Direction.ERROR, "Attiva il servizio Accessibilità per i tasti direzionali")
                }
            }
        }
    }

    // Listen to incoming lines from M5Paper
    LaunchedEffect(Unit) {
        usbController.incomingLines.collect { line ->
            addLog(Direction.RX, line)

            // Parse STATUS packet
            if (line.startsWith("STATUS:")) {
                try {
                    val params = line.substring(7).split(",")
                    var batMv = telemetry.batteryMv
                    var batPct = telemetry.batteryPct
                    var temp = telemetry.temp
                    var hum = telemetry.hum
                    var uptime = telemetry.uptime

                    for (p in params) {
                        val kv = p.split("=")
                        if (kv.size == 2) {
                            when (kv[0].trim()) {
                                "bat_mv" -> batMv = kv[1].trim().toIntOrNull() ?: batMv
                                "bat_pct" -> batPct = kv[1].trim().toIntOrNull() ?: batPct
                                "temp" -> temp = kv[1].trim().toFloatOrNull() ?: temp
                                "hum" -> hum = kv[1].trim().toFloatOrNull() ?: hum
                                "uptime" -> uptime = kv[1].trim().toLongOrNull() ?: uptime
                            }
                        }
                    }
                    telemetry = telemetry.copy(
                        batteryMv = batMv,
                        batteryPct = batPct,
                        temp = temp,
                        hum = hum,
                        uptime = uptime
                    )
                } catch (e: Exception) {
                    addLog(Direction.ERROR, "Parse telemetry failed: ${e.message}")
                }
            } else if (line == "EVENT:BTN_PUSH") {
                // CENTRAL BUTTON PRESSED ON M5PAPER
                telemetry = telemetry.copy(lastTouch = "Wheel Click (BTN_PUSH)")
                addLog(Direction.RX, "EVENT:BTN_PUSH -> Tasto centrale premuto su M5Paper!")
                if (isCaptureRunning) {
                    coroutineScope.launch {
                        triggerScreenshotCapture()
                    }
                } else {
                    addLog(Direction.ERROR, "Tasto premuto su M5Paper ma la cattura schermo non è abilitata sull'app!")
                    onSend("MSG:Abilita screenshot sull'app Android!")
                }
            } else if (line.startsWith("TOUCH:")) {
                telemetry = telemetry.copy(lastTouch = line)
                try {
                    // Expected format: TOUCH:x=480,y=270,size=1,scr=1
                    val params = line.substring(6).split(",")
                    var touchX = -1
                    var touchY = -1
                    var scrParam: Int? = null
                    for (p in params) {
                        val kv = p.split("=")
                        if (kv.size == 2) {
                            when (kv[0].trim()) {
                                "x" -> touchX = kv[1].trim().toIntOrNull() ?: -1
                                "y" -> touchY = kv[1].trim().toIntOrNull() ?: -1
                                "scr" -> scrParam = kv[1].trim().toIntOrNull()
                            }
                        }
                    }

                    // A touch is during screenshot if explicitly marked scr=1, or if app is in screenshot mode
                    val isScreenshotMode = (scrParam == 1) || (scrParam == null && isShowingScreenshot)

                    if (touchX >= 0 && touchY >= 0) {
                        if (isScreenshotMode) {
                            val mapped = screenshotManager.mapM5PaperTouchToPhone(touchX, touchY)
                            if (mapped != null) {
                                val (phoneX, phoneY) = mapped
                                if (RemoteTouchAccessibilityService.isRunning()) {
                                    val ok = RemoteTouchAccessibilityService.injectTap(phoneX, phoneY) {
                                        // Tap successfully executed on phone: refresh screenshot after short delay
                                        if (isCaptureRunning) {
                                            coroutineScope.launch {
                                                delay(400)
                                                triggerScreenshotCapture()
                                            }
                                        }
                                    }
                                    if (ok) {
                                        addLog(Direction.INFO, "👆 Tocco inviato allo smartphone a (${phoneX.toInt()}, ${phoneY.toInt()}) [M5Paper: $touchX, $touchY]")
                                    } else {
                                        addLog(Direction.ERROR, "Iniezione tocco fallita a (${phoneX.toInt()}, ${phoneY.toInt()})")
                                    }
                                } else {
                                    addLog(Direction.INFO, "👆 Tocco screenshot ($touchX, $touchY -> Phone ${phoneX.toInt()}, ${phoneY.toInt()}) [Attiva l'Accessibilità per cliccare]")
                                }
                            } else {
                                addLog(Direction.INFO, "Tocco M5Paper ($touchX, $touchY) fuori dai bordi dello screenshot")
                            }
                        } else {
                            addLog(Direction.INFO, "👆 Tocco M5Paper sulla Dashboard a ($touchX, $touchY)")
                        }
                    }
                } catch (e: Exception) {
                    addLog(Direction.ERROR, "Errore elaborazione tocco: ${e.message}")
                }
            } else if (line == "EVENT:BTN_UP" || line == "EVENT:BTN_LEFT") {
                telemetry = telemetry.copy(lastTouch = "Wheel UP (Tasto Su)")
                addLog(Direction.RX, "$line -> Tasto Su premuto su M5Paper")
                handleUpDownKey(isUp = true)
            } else if (line == "EVENT:BTN_DOWN" || line == "EVENT:BTN_RIGHT") {
                telemetry = telemetry.copy(lastTouch = "Wheel DOWN (Tasto Giù)")
                addLog(Direction.RX, "$line -> Tasto Giù premuto su M5Paper")
                handleUpDownKey(isUp = false)
            } else if (line == "EVENT:DASHBOARD" || line == "ACK:DASHBOARD") {
                isShowingScreenshot = false
                telemetry = telemetry.copy(lastTouch = line)
                addLog(Direction.RX, "$line -> Ritorno alla Dashboard")
            } else if (line.startsWith("EVENT:")) {
                telemetry = telemetry.copy(lastTouch = line)
            }
        }
    }

    // Monitor connection status changes for logging
    LaunchedEffect(connectionStatus) {
        when (val status = connectionStatus) {
            is ConnectionStatus.Connected -> {
                val speedDesc = if (status.baudRate >= 1000000) "%.1f Mbps".format(status.baudRate / 1000000.0) else "${status.baudRate / 1000} Kbps"
                addLog(Direction.INFO, "Connected: ${status.deviceName} @ ${status.baudRate} Baud ($speedDesc) (VID: ${status.vid}, PID: ${status.pid})")
                onSend("STATUS?")
            }
            is ConnectionStatus.Disconnected -> {
                addLog(Direction.INFO, "USB Disconnected")
            }
            is ConnectionStatus.Connecting -> {
                addLog(Direction.INFO, "Connecting to USB device @ $selectedBaudRate baud...")
            }
            is ConnectionStatus.Error -> {
                addLog(Direction.ERROR, status.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "M5Paper USB Companion",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionStatus) {
                                        is ConnectionStatus.Connected -> Color(0xFF22C55E)
                                        is ConnectionStatus.Connecting -> Color(0xFFEAB308)
                                        else -> Color(0xFFEF4444)
                                    }
                                )
                        )
                    }
                },
                actions = {
                    if (connectionStatus is ConnectionStatus.Connected) {
                        IconButton(onClick = onDisconnectClick) {
                            Icon(Icons.Default.UsbOff, contentDescription = "Disconnect")
                        }
                    } else {
                        IconButton(onClick = { onConnectClick(selectedBaudRate) }) {
                            Icon(Icons.Default.Usb, contentDescription = "Connect")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Connection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (val s = connectionStatus) {
                                    is ConnectionStatus.Connected -> "Connected: ${s.deviceName}"
                                    is ConnectionStatus.Connecting -> "Connecting..."
                                    is ConnectionStatus.Disconnected -> "Disconnected"
                                    is ConnectionStatus.Error -> "Error: ${s.message}"
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                            Text(
                                text = when (val s = connectionStatus) {
                                    is ConnectionStatus.Connected -> "${s.baudRate} Baud (${if (s.baudRate >= 1000000) "%.1f Mbps".format(s.baudRate / 1000000.0) else "${s.baudRate / 1000} Kbps"}) | 8N1"
                                    else -> "Target Speed: ${selectedBaudRate} Baud (${if (selectedBaudRate >= 1000000) "%.1f Mbps".format(selectedBaudRate / 1000000.0) else "${selectedBaudRate / 1000} Kbps"})"
                                },
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        if (connectionStatus is ConnectionStatus.Connected) {
                            Button(
                                onClick = onDisconnectClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Text("Disconnect")
                            }
                        } else {
                            Button(
                                onClick = { onConnectClick(selectedBaudRate) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Connect")
                            }
                        }
                    }

                    if (connectionStatus !is ConnectionStatus.Connected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Speed:", fontSize = 12.sp, color = Color.Gray)
                            listOf(
                                2000000 to "2 Mbps (Max)",
                                921600 to "921.6K",
                                115200 to "115.2K"
                            ).forEach { (baud, label) ->
                                FilterChip(
                                    selected = selectedBaudRate == baud,
                                    onClick = { selectedBaudRate = baud },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // 2. SCREENSHOT & SCREEN MIRRORING CARD (FEATURE REQUEST)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Screenshot,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Screenshot & Controllo Touch",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Status Badges
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (isCaptureRunning) "Cattura ON" else "Cattura OFF",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isCaptureRunning) Color(0xFF22C55E) else Color(0xFFEF4444))
                                    )
                                }
                            )

                            AssistChip(
                                onClick = {
                                    if (!isRemoteTouchActive) {
                                        RemoteTouchAccessibilityService.openAccessibilitySettings(context)
                                        Toast.makeText(context, "Attiva 'M5Paper USB Companion' in Accessibilità", Toast.LENGTH_LONG).show()
                                    }
                                },
                                label = {
                                    Text(
                                        if (isRemoteTouchActive) "Touch ON" else "Touch OFF",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isRemoteTouchActive) Color(0xFF22C55E) else Color(0xFFEF4444))
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        text = "Premi la rotellina su M5Paper per acquisire lo schermo. Tocca il display di M5Paper durante lo screenshot per interagire con lo smartphone.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!isRemoteTouchActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Controllo Touch Remoto non attivo",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Attiva l'Accessibilità per cliccare sullo smartphone da M5Paper.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        RemoteTouchAccessibilityService.openAccessibilitySettings(context)
                                        Toast.makeText(context, "Attiva 'M5Paper USB Companion' in Accessibilità", Toast.LENGTH_LONG).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Attiva", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isCaptureRunning) {
                            Button(
                                onClick = {
                                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Abilita Cattura Schermo")
                            }
                        } else {
                            Button(
                                onClick = { triggerScreenshotCapture() },
                                modifier = Modifier.weight(1f),
                                enabled = connectionStatus is ConnectionStatus.Connected && transferState !is ScreenshotTransferState.Sending
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Invia Ora")
                            }

                            OutlinedButton(
                                onClick = {
                                    ScreenCaptureService.stopService(context)
                                    isShowingScreenshot = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.StopScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disabilita")
                            }
                        }
                    }

                    // Format mode selector
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Formato:", fontSize = 11.sp, color = Color.Gray)
                        FilterChip(
                            selected = selectedFitMode == ScreenshotFitMode.ROTATE_90_FILL,
                            onClick = {
                                selectedFitMode = ScreenshotFitMode.ROTATE_90_FILL
                                screenshotManager.fitMode = ScreenshotFitMode.ROTATE_90_FILL
                            },
                            label = { Text("Ruota 90° (Pieno Schermo)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedFitMode == ScreenshotFitMode.FIT_CENTER,
                            onClick = {
                                selectedFitMode = ScreenshotFitMode.FIT_CENTER
                                screenshotManager.fitMode = ScreenshotFitMode.FIT_CENTER
                            },
                            label = { Text("Adatta Orizzontale", fontSize = 11.sp) }
                        )
                    }

                    // Tasti Su/Giù action mode selector
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Tasti Su/Giù:", fontSize = 11.sp, color = Color.Gray)
                        UpDownKeyAction.values().forEach { action ->
                            FilterChip(
                                selected = selectedKeyAction == action,
                                onClick = { selectedKeyAction = action },
                                label = { Text(action.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    // Transfer State Indicator
                    when (val state = transferState) {
                        is ScreenshotTransferState.Capturing -> {
                            Text("Cattura immagine in corso...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        is ScreenshotTransferState.Sending -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Text(
                                    "Invio a M5Paper: ${state.sentBytes / 1024} KB / ${state.totalBytes / 1024} KB (${(state.progress * 100).toInt()}%)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is ScreenshotTransferState.Success -> {
                            Text(
                                "✓ Screenshot visualizzato a schermo intero (${state.totalBytes / 1024} KB in ${state.durationMs}ms)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF22C55E)
                            )
                        }
                        is ScreenshotTransferState.Error -> {
                            Text("✕ Errore invio: ${state.message}", fontSize = 12.sp, color = Color(0xFFEF4444))
                        }
                        is ScreenshotTransferState.Idle -> {}
                    }
                }
            }

            // 3. Telemetry Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "M5Paper Telemetry",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryBadge("Battery", "${telemetry.batteryPct}% (${telemetry.batteryMv}mV)")
                        TelemetryBadge("Temp", "%.1f °C".format(telemetry.temp))
                        TelemetryBadge("Humidity", "%.0f %%".format(telemetry.hum))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last Event: ${telemetry.lastTouch}",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 4. Quick Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val cmd = "PING"
                        if (onSend(cmd)) addLog(Direction.TX, cmd)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ping")
                }
                FilledTonalButton(
                    onClick = {
                        val cmd = "STATUS?"
                        if (onSend(cmd)) addLog(Direction.TX, cmd)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Status")
                }
                FilledTonalButton(
                    onClick = {
                        val cmd = "REFRESH"
                        if (onSend(cmd)) {
                            addLog(Direction.TX, cmd)
                            isShowingScreenshot = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
                FilledTonalButton(
                    onClick = {
                        val cmd = "DASHBOARD"
                        if (onSend(cmd)) {
                            addLog(Direction.TX, cmd)
                            isShowingScreenshot = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Home")
                }
            }

            // 5. Send Message Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Send Message to E-ink Screen",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Write message here...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            shape = RoundedCornerShape(8.dp)
                        )
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    val cmd = "MSG:$inputText"
                                    if (onSend(cmd)) {
                                        addLog(Direction.TX, cmd)
                                        isShowingScreenshot = false
                                        inputText = ""
                                    }
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Quick Presets:", fontSize = 12.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetChip("Meeting 15:00") {
                            val cmd = "MSG:Meeting at 15:00 in Room B"
                            if (onSend(cmd)) {
                                addLog(Direction.TX, cmd)
                                isShowingScreenshot = false
                            }
                        }
                        PresetChip("Do Not Disturb") {
                            val cmd = "MSG:Do Not Disturb - In Deep Work"
                            if (onSend(cmd)) {
                                addLog(Direction.TX, cmd)
                                isShowingScreenshot = false
                            }
                        }
                        PresetChip("Coffee ☕") {
                            val cmd = "MSG:Coffee Break time! ☕"
                            if (onSend(cmd)) {
                                addLog(Direction.TX, cmd)
                                isShowingScreenshot = false
                            }
                        }
                    }
                }
            }

            // 6. Live USB Console Log
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "USB Serial Log (${logs.size})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        TextButton(onClick = { logs.clear() }) {
                            Text("Clear Log", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E293B))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logs) { entry ->
                            LogItemView(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryBadge(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF334155))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun PresetChip(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(text, fontSize = 11.sp)
    }
}

@Composable
fun LogItemView(entry: LogEntry) {
    val (color, prefix) = when (entry.direction) {
        Direction.TX -> Color(0xFF38BDF8) to "[TX]"
        Direction.RX -> Color(0xFF4ADE80) to "[RX]"
        Direction.INFO -> Color(0xFFFBBF24) to "[SYS]"
        Direction.ERROR -> Color(0xFFF87171) to "[ERR]"
    }

    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = "${entry.timestamp} $prefix ",
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = entry.text,
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
