/**
 * M5Paper USB Companion Firmware
 * 
 * Communicates with an Android device over USB Serial (UART0 at 115200 baud).
 * Receives messages, commands, and displays rich formatted text on the E-ink display.
 * Reports touch events, button presses, battery status, and sensor readings back to Android.
 */

#include <M5EPD.h>

// Canvas buffer for the 960x540 landscape e-ink display
M5EPD_Canvas canvas(&M5.EPD);

// Hardware Maximum Baud Rate (CP2104 / CH9102 & ESP32 UART: 2 Mbps)
static const uint32_t SERIAL_BAUD_RATE = 2000000;

// Serial line buffer
static const size_t BUFFER_SIZE = 512;
char rxBuffer[BUFFER_SIZE];
size_t rxIndex = 0;

// Screenshot Image Buffer (stored in PSRAM)
static const size_t MAX_IMG_SIZE = 512 * 1024; // 512 KB max for JPEG
uint8_t* imgBuffer = nullptr;
size_t imgExpectedSize = 0;
size_t imgBufferPos = 0;
bool isShowingScreenshot = false;

// Application State
String currentMessage = "Ready for Android connection.\nConnect USB cable and tap 'Connect'.";
String lastSender = "System";
String messageHistory[4];
int historyCount = 0;
int updateCounter = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastTouchTime = 0;
bool wasFingerDown = false;
String lastTouchInfo = "None";

// Helper: battery percentage calculation
int getBatteryPercentage() {
    uint32_t vol = M5.getBatteryVoltage();
    if (vol <= 3300) return 0;
    if (vol >= 4150) return 100;
    return (int)((vol - 3300) * 100 / 850);
}

// Add message to history
void pushHistory(const String& msg) {
    if (historyCount < 4) {
        messageHistory[historyCount++] = msg;
    } else {
        for (int i = 0; i < 3; i++) {
            messageHistory[i] = messageHistory[i + 1];
        }
        messageHistory[3] = msg;
    }
}

// Render the complete UI on the e-ink screen
void renderUI(bool fullRefresh = false) {
    canvas.clear();

    // 1. Header Bar (Top)
    canvas.fillRect(0, 0, 960, 50, 15); // Dark header bar
    canvas.setTextSize(3);
    canvas.setTextColor(0, 15); // White text on black background
    canvas.drawString("M5Paper  <--[USB]-->  Android", 20, 14);

    // Header info: Battery & Sensor
    M5.SHT30.UpdateData();
    float temp = M5.SHT30.GetTemperature();
    float hum = M5.SHT30.GetRelHumidity();
    int batPct = getBatteryPercentage();

    char headerStats[64];
    snprintf(headerStats, sizeof(headerStats), "Bat: %d%%  |  %.1f C  |  %.0f%% RH", batPct, temp, hum);
    canvas.setTextSize(2);
    canvas.drawString(headerStats, 620, 18);

    // 2. Active Message Card
    canvas.setTextSize(2);
    canvas.setTextColor(15, 0); // Black text on white background
    canvas.drawRect(20, 70, 920, 240, 15);
    canvas.drawRect(21, 71, 918, 238, 15); // Double border

    canvas.fillRect(20, 70, 920, 32, 10); // Card header banner
    canvas.setTextColor(0, 10);
    canvas.drawString("  LATEST MESSAGE RECEIVED FROM ANDROID", 30, 78);

    canvas.setTextColor(15, 0);
    canvas.setTextSize(3);

    // Wrap and print the active message
    int textY = 120;
    int lineStart = 0;
    int len = currentMessage.length();
    while (lineStart < len && textY <= 270) {
        int lineEnd = lineStart + 42; // ~42 characters per line at size 3
        if (lineEnd > len) lineEnd = len;
        
        // Find line break or space to wrap nicely
        int nlPos = currentMessage.indexOf('\n', lineStart);
        if (nlPos != -1 && nlPos < lineEnd) {
            lineEnd = nlPos;
        } else if (lineEnd < len) {
            int spacePos = currentMessage.lastIndexOf(' ', lineEnd);
            if (spacePos > lineStart) {
                lineEnd = spacePos;
            }
        }

        String sub = currentMessage.substring(lineStart, lineEnd);
        sub.trim();
        if (sub.length() > 0) {
            canvas.drawString(sub.c_str(), 40, textY);
            textY += 36;
        }

        if (nlPos != -1 && nlPos == lineEnd) {
            lineStart = lineEnd + 1;
        } else {
            lineStart = (lineEnd < len && currentMessage.charAt(lineEnd) == ' ') ? lineEnd + 1 : lineEnd;
        }
    }

    // 3. Message History Box (Bottom Left)
    canvas.drawRect(20, 330, 580, 180, 15);
    canvas.fillRect(20, 330, 580, 28, 5);
    canvas.setTextSize(2);
    canvas.setTextColor(15, 5);
    canvas.drawString("  RECENT LOG / HISTORY", 25, 336);

    canvas.setTextColor(15, 0);
    int histY = 368;
    for (int i = 0; i < historyCount; i++) {
        String item = String(i + 1) + ". " + messageHistory[i];
        if (item.length() > 46) item = item.substring(0, 43) + "...";
        canvas.drawString(item.c_str(), 35, histY);
        histY += 28;
    }
    if (historyCount == 0) {
        canvas.drawString("(No previous messages)", 35, histY);
    }

    // 4. Device Status & Interaction Panel (Bottom Right)
    canvas.drawRect(620, 330, 320, 180, 15);
    canvas.fillRect(620, 330, 320, 28, 5);
    canvas.setTextColor(15, 5);
    canvas.drawString("  DEVICE & TELEMETRY", 625, 336);

    canvas.setTextColor(15, 0);
    canvas.setTextSize(2);
    char buf[64];
    snprintf(buf, sizeof(buf), "Bat: %d mV (%d%%)", M5.getBatteryVoltage(), batPct);
    canvas.drawString(buf, 635, 370);

    snprintf(buf, sizeof(buf), "Uptime: %lu s", millis() / 1000);
    canvas.drawString(buf, 635, 400);

    snprintf(buf, sizeof(buf), "Touch: %s", lastTouchInfo.c_str());
    canvas.drawString(buf, 635, 430);

    canvas.drawString("USB: 2M Baud (2 Mbps)", 635, 460);

    // 5. Footer Line
    canvas.setTextSize(1);
    canvas.drawString("Touch screen or push wheel switch to send event to Android", 300, 522);

    // Push canvas to e-ink
    updateCounter++;
    if (fullRefresh || (updateCounter % 15 == 0)) {
        // Full refresh periodically to eliminate ghosting
        canvas.pushCanvas(0, 0, UPDATE_MODE_GC16);
    } else {
        // Fast 4-gray partial refresh for crisp text and fast update
        canvas.pushCanvas(0, 0, UPDATE_MODE_DU4);
    }
}

// Process an incoming command line from Android
void processCommand(const String& cmd) {
    if (cmd.startsWith("MSG:")) {
        isShowingScreenshot = false;
        String msg = cmd.substring(4);
        if (currentMessage.length() > 0 && currentMessage != "Ready for Android connection.\nConnect USB cable and tap 'Connect'.") {
            pushHistory(currentMessage);
        }
        currentMessage = msg;
        renderUI(false);
        Serial.println("ACK:MSG");
    }
    else if (cmd.equalsIgnoreCase("CLEAR")) {
        isShowingScreenshot = false;
        if (currentMessage.length() > 0) {
            pushHistory(currentMessage);
        }
        currentMessage = "(Screen cleared by Android)";
        renderUI(false);
        Serial.println("ACK:CLEAR");
    }
    else if (cmd.equalsIgnoreCase("PING")) {
        Serial.println("PONG");
    }
    else if (cmd.equalsIgnoreCase("STATUS?")) {
        M5.SHT30.UpdateData();
        float temp = M5.SHT30.GetTemperature();
        float hum = M5.SHT30.GetRelHumidity();
        uint32_t bat_mv = M5.getBatteryVoltage();
        int bat_pct = getBatteryPercentage();
        unsigned long uptime = millis() / 1000;

        char resp[128];
        snprintf(resp, sizeof(resp), "STATUS:bat_mv=%u,bat_pct=%d,temp=%.1f,hum=%.1f,uptime=%lu",
                 bat_mv, bat_pct, temp, hum, uptime);
        Serial.println(resp);
    }
    else if (cmd.startsWith("BUZZ")) {
        Serial.println("ACK:BUZZ");
    }
    else if (cmd.equalsIgnoreCase("REFRESH")) {
        isShowingScreenshot = false;
        renderUI(true); // Force full GC16 refresh
        Serial.println("ACK:REFRESH");
    }
    else if (cmd.equalsIgnoreCase("DASHBOARD")) {
        isShowingScreenshot = false;
        renderUI(true);
        Serial.println("ACK:DASHBOARD");
    }
    else if (cmd.startsWith("IMG_BEGIN:")) {
        imgExpectedSize = cmd.substring(10).toInt();
        imgBufferPos = 0;
        if (imgBuffer != nullptr && imgExpectedSize > 0 && imgExpectedSize <= MAX_IMG_SIZE) {
            Serial.println("ACK:IMG_BEGIN");
        } else {
            Serial.println("ERR:IMG_SIZE");
        }
    }
    else if (cmd.startsWith("IMG_CHUNK:")) {
        size_t chunkLen = cmd.substring(10).toInt();
        if (imgBuffer != nullptr && (imgBufferPos + chunkLen) <= MAX_IMG_SIZE) {
            size_t bytesRead = 0;
            unsigned long t0 = millis();
            while (bytesRead < chunkLen && (millis() - t0 < 4000)) {
                if (Serial.available()) {
                    size_t n = Serial.readBytes((char*)(imgBuffer + imgBufferPos + bytesRead), chunkLen - bytesRead);
                    bytesRead += n;
                    t0 = millis();
                } else {
                    yield();
                }
            }
            if (bytesRead == chunkLen) {
                imgBufferPos += chunkLen;
                Serial.println("ACK:CHUNK");
                if (imgBufferPos >= imgExpectedSize) {
                    isShowingScreenshot = true;
                    canvas.clear();
                    // Draw full-screen JPEG (960x540)
                    bool ok = canvas.drawJpg(imgBuffer, imgExpectedSize, 0, 0);
                    if (ok) {
                        canvas.pushCanvas(0, 0, UPDATE_MODE_GC16);
                        Serial.println("ACK:IMG_DONE");
                    } else {
                        Serial.println("ERR:JPG_DECODE");
                    }
                    imgExpectedSize = 0;
                    imgBufferPos = 0;
                }
            } else {
                Serial.print("ERR:CHUNK_TIMEOUT:got=");
                Serial.print(bytesRead);
                Serial.print(":expected=");
                Serial.println(chunkLen);
                imgExpectedSize = 0;
                imgBufferPos = 0;
            }
        } else {
            Serial.println("ERR:CHUNK_OVERFLOW");
            imgExpectedSize = 0;
            imgBufferPos = 0;
        }
    }
    else if (cmd.equalsIgnoreCase("IMG_ABORT")) {
        imgExpectedSize = 0;
        imgBufferPos = 0;
        Serial.println("ACK:IMG_ABORT");
    }
    else {
        // Echo unknown command
        Serial.print("ERR:UNKNOWN_CMD:");
        Serial.println(cmd);
    }
}

void setup() {
    M5.begin();
    M5.EPD.SetRotation(0); // 960x540 landscape
    M5.TP.SetRotation(0);  // 960x540 landscape touch
    M5.EPD.Clear(true);
    M5.RTC.begin();
    M5.SHT30.Begin();

    // Standard UART0 via CP2104 / CH9102 USB-C port at 2 Mbps (Maximum Hardware Speed)
    // 16 KB RX buffer ensures high-speed burst packets are not dropped
    Serial.setRxBufferSize(16384);
    Serial.begin(SERIAL_BAUD_RATE);
    Serial.setTimeout(1500);

    // Prepare e-ink canvas buffer in PSRAM
    canvas.createCanvas(960, 540);

    // Allocate screenshot buffer in PSRAM
    imgBuffer = (uint8_t*)ps_malloc(MAX_IMG_SIZE);
    if (!imgBuffer) {
        imgBuffer = (uint8_t*)malloc(MAX_IMG_SIZE);
    }

    // Initial render
    renderUI(true);

    // Inform host device that M5Paper is ready
    delay(200);
    Serial.println("READY:M5Paper v1.0");
}

void loop() {
    // 1. Check for incoming USB serial data
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\r') continue; // Ignore carriage return to ensure clean line ends
        if (c == '\n') {
            if (rxIndex > 0) {
                rxBuffer[rxIndex] = '\0';
                String line = String(rxBuffer);
                line.trim();
                rxIndex = 0;
                if (line.length() > 0) {
                    processCommand(line);
                }
            }
        } else {
            if (rxIndex < BUFFER_SIZE - 1) {
                rxBuffer[rxIndex++] = c;
            }
        }
    }

    // While an image transfer is actively in progress, prioritize high-speed serial
    if (imgExpectedSize > 0 && imgBufferPos < imgExpectedSize) {
        yield();
        return;
    }

    M5.update();

    // 2. Check for Touch Screen Interaction
    if (M5.TP.available()) {
        M5.TP.update();
        if (!M5.TP.isFingerUp()) {
            tp_finger_t finger = M5.TP.readFinger(0);
            
            // Debounce touch events and detect edge
            if (!wasFingerDown || (millis() - lastTouchTime > 350)) {
                lastTouchTime = millis();
                wasFingerDown = true;

                int screenX = finger.x;
                int screenY = finger.y;

                // Clamp to screen bounds
                if (screenX < 0) screenX = 0;
                if (screenX >= 960) screenX = 959;
                if (screenY < 0) screenY = 0;
                if (screenY >= 540) screenY = 539;

                char touchStr[64];
                snprintf(touchStr, sizeof(touchStr), "TOUCH:x=%d,y=%d,size=%d,scr=%d", 
                         screenX, screenY, finger.size, isShowingScreenshot ? 1 : 0);
                Serial.println(touchStr);

                lastTouchInfo = String("X:") + screenX + " Y:" + screenY;
                // During screenshot display, do NOT exit screenshot mode or overwrite screen with dashboard!
                if (!isShowingScreenshot) {
                    renderUI(false);
                }
            }
        } else {
            wasFingerDown = false;
        }
        M5.TP.flush();
    }

    // 3. Check Physical Buttons (Wheel Switch)
    static bool longPressHandled = false;
    if (M5.BtnP.isPressed()) {
        if (!longPressHandled && M5.BtnP.pressedFor(1000)) {
            longPressHandled = true;
            if (isShowingScreenshot) {
                isShowingScreenshot = false;
                renderUI(true);
                Serial.println("EVENT:DASHBOARD");
            }
        }
    } else {
        if (M5.BtnP.wasReleased()) {
            if (!longPressHandled) {
                Serial.println("EVENT:BTN_PUSH");
                lastTouchInfo = "Wheel Click";
                // Short click requests a fresh screenshot from Android
            }
        }
        longPressHandled = false;
    }

    if (M5.BtnL.wasPressed()) {
        Serial.println("EVENT:BTN_UP");
        lastTouchInfo = "Key UP";
        if (!isShowingScreenshot) {
            renderUI(false);
        }
    }
    if (M5.BtnR.wasPressed()) {
        Serial.println("EVENT:BTN_DOWN");
        lastTouchInfo = "Key DOWN";
        if (!isShowingScreenshot) {
            renderUI(false);
        }
    }

    // Small delay to prevent tight loop spinning when idle
    delay(2);
}
