package com.example.m5paperusb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.charset.StandardCharsets

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val deviceName: String, val vid: Int, val pid: Int, val baudRate: Int) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

class UsbSerialController(private val context: Context) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbSerialController"
        const val ACTION_USB_PERMISSION = "com.example.m5paperusb.USB_PERMISSION"
        const val DEFAULT_BAUD_RATE = 2000000 // Hardware maximum: 2 Mbps for CP2104 / CH9102 & ESP32
        private const val READ_WAIT_MILLIS = 200
        private const val WRITE_WAIT_MILLIS = 2000
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    var activeBaudRate: Int = DEFAULT_BAUD_RATE
        private set

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val lineBuffer = StringBuilder()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _incomingLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingLines: SharedFlow<String> = _incomingLines.asSharedFlow()

    private val lineListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addLineListener(listener: (String) -> Unit) {
        lineListeners.add(listener)
    }

    fun removeLineListener(listener: (String) -> Unit) {
        lineListeners.remove(listener)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                connectToDevice(device)
                            }
                        } else {
                            _connectionStatus.value = ConnectionStatus.Error("USB Permission Denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                    _connectionStatus.value = ConnectionStatus.Disconnected
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun scanAndConnect(baudRate: Int = DEFAULT_BAUD_RATE) {
        activeBaudRate = baudRate
        _connectionStatus.value = ConnectionStatus.Connecting
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        if (availableDrivers.isEmpty()) {
            _connectionStatus.value = ConnectionStatus.Error("No compatible USB Serial device found. Ensure M5Paper is connected via OTG.")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } else {
            connectToDevice(device)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val prober = UsbSerialProber.getDefaultProber()
        val driver = prober.probeDevice(device) ?: run {
            _connectionStatus.value = ConnectionStatus.Error("Driver not found for device: ${device.deviceName}")
            return
        }

        val connection = usbManager.openDevice(driver.device) ?: run {
            _connectionStatus.value = ConnectionStatus.Error("Unable to open USB connection. Check permissions.")
            return
        }

        try {
            val port = driver.ports[0] // M5Paper UART is on port 0
            port.open(connection)
            port.setParameters(activeBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            
            // Set DTR and RTS to true to enable communication through CP2104/CH9102
            try {
                port.dtr = true
                port.rts = true
            } catch (ignored: Exception) {
                // Some chips might not support RTS/DTR setting directly
            }

            usbSerialPort = port
            
            // Start background IO Manager
            ioManager = SerialInputOutputManager(port, this).apply {
                start()
            }

            _connectionStatus.value = ConnectionStatus.Connected(
                deviceName = device.productName ?: device.deviceName,
                vid = device.vendorId,
                pid = device.productId,
                baudRate = activeBaudRate
            )
            Log.d(TAG, "Connected to ${device.deviceName} at $activeBaudRate baud.")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to USB device", e)
            disconnect()
            _connectionStatus.value = ConnectionStatus.Error("Connection failed: ${e.localizedMessage}")
        }
    }

    fun sendLine(message: String): Boolean {
        val port = usbSerialPort ?: return false
        val formatted = if (message.endsWith("\n")) message else "$message\n"
        return try {
            val data = formatted.toByteArray(StandardCharsets.UTF_8)
            port.write(data, WRITE_WAIT_MILLIS)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
            disconnect()
            _connectionStatus.value = ConnectionStatus.Error("Write failed: ${e.localizedMessage}")
            false
        }
    }

    fun writeRawBytes(data: ByteArray, timeoutMillis: Int = WRITE_WAIT_MILLIS): Boolean {
        val port = usbSerialPort ?: return false
        return try {
            port.write(data, timeoutMillis)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write raw bytes failed", e)
            disconnect()
            _connectionStatus.value = ConnectionStatus.Error("Write failed: ${e.localizedMessage}")
            false
        }
    }

    suspend fun sendCommandAndWaitResponse(
        command: String,
        expectedPrefix: String,
        timeoutMillis: Long = 3000
    ): Boolean {
        val deferred = CompletableDeferred<String>()
        val listener: (String) -> Unit = { line ->
            if (line.startsWith(expectedPrefix) || line.startsWith("ERR:")) {
                deferred.complete(line)
            }
        }
        addLineListener(listener)
        return try {
            if (!sendLine(command)) return false
            val resp = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            resp?.startsWith(expectedPrefix) == true
        } finally {
            removeLineListener(listener)
        }
    }

    suspend fun sendChunkAndWaitAck(
        chunk: ByteArray,
        timeoutMillis: Long = 4000
    ): Boolean {
        val deferred = CompletableDeferred<String>()
        val listener: (String) -> Unit = { line ->
            if (line == "ACK:CHUNK" || line.startsWith("ERR:")) {
                deferred.complete(line)
            }
        }
        addLineListener(listener)
        return try {
            val header = "IMG_CHUNK:${chunk.size}\n".toByteArray(StandardCharsets.UTF_8)
            val packet = ByteArray(header.size + chunk.size)
            System.arraycopy(header, 0, packet, 0, header.size)
            System.arraycopy(chunk, 0, packet, header.size, chunk.size)

            if (!writeRawBytes(packet, timeoutMillis.toInt())) return false
            val resp = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            if (resp != null && resp.startsWith("ERR:")) {
                Log.e(TAG, "M5Paper returned chunk error: $resp")
            }
            resp == "ACK:CHUNK"
        } finally {
            removeLineListener(listener)
        }
    }

    fun disconnect() {
        try {
            ioManager?.listener = null
            ioManager?.stop()
        } catch (ignored: Exception) {}
        ioManager = null

        try {
            usbSerialPort?.close()
        } catch (ignored: Exception) {}
        usbSerialPort = null

        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    fun cleanUp() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {}
    }

    override fun onNewData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val chunk = String(data, StandardCharsets.UTF_8)
        
        synchronized(lineBuffer) {
            lineBuffer.append(chunk)
            var newlineIndex = lineBuffer.indexOf('\n')
            while (newlineIndex != -1) {
                val line = lineBuffer.substring(0, newlineIndex).trimEnd('\r')
                lineBuffer.delete(0, newlineIndex + 1)
                if (line.isNotEmpty()) {
                    _incomingLines.tryEmit(line)
                    for (listener in lineListeners) {
                        try {
                            listener(line)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in line listener", e)
                        }
                    }
                }
                newlineIndex = lineBuffer.indexOf('\n')
            }
        }
    }

    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial IO Manager error", e)
        disconnect()
        _connectionStatus.value = ConnectionStatus.Error("Communication error: ${e?.localizedMessage ?: "Unknown"}")
    }
}
