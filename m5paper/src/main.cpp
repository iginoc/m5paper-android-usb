/**
 * M5Paper USB Companion Firmware
 *
 * Communicates with an Android device over USB Serial at 2 Mbps.
 * Receives messages/commands and displays rich text on the e-ink display.
 * Reports touch events, button presses, battery and sensor data back to Android.
 *
 * Remote-desktop view: every touch is forwarded to Android as TOUCH:...,scr=1.
 * The on-screen keyboard is the one drawn by Android inside the screenshot.
 * Long press the wheel button → returns to the dashboard.
 */

#include <M5EPD.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Preferences.h>

// Canvas for the 960×540 landscape e-ink display
M5EPD_Canvas canvas(&M5.EPD);

static const uint32_t SERIAL_BAUD_RATE = 2000000;

static const size_t BUFFER_SIZE = 512;
char   rxBuffer[BUFFER_SIZE];
size_t rxIndex = 0;

// Screenshot buffer (PSRAM)
static const size_t MAX_IMG_SIZE = 512 * 1024;
uint8_t* imgBuffer     = nullptr;
size_t imgExpectedSize = 0;
size_t imgBufferPos    = 0;
size_t lastImgSize     = 0;   // size of the last complete JPEG kept in imgBuffer (0 = none)
bool isShowingScreenshot = false;

// Compose box (text being typed on the Android keyboard), drawn over the screenshot
static const int COMPOSE_Y = 300;   // <-- top of the box: put it just above the keyboard
static const int COMPOSE_H =  50;
bool   composeVisible = false;
String composeText    = "";

// Application state
String currentMessage = "Ready for Android connection.\nConnect USB cable and tap 'Connect'.";
String messageHistory[4];
int   historyCount   = 0;
int   updateCounter  = 0;
unsigned long lastTouchTime = 0;
bool  wasFingerDown  = false;
String lastTouchInfo = "None";
static const uint16_t WIFI_PORT = 3333;
static const uint16_t WIFI_DISCOVERY_PORT = 3334;
WiFiServer wifiServer(WIFI_PORT);
WiFiUDP discoveryUdp;
WiFiClient wifiClient;
Preferences preferences;
bool wifiServerStarted = false;
bool discoveryStarted = false;
bool wifiStopPending = false;
Stream* responseStream = nullptr;
char wifiRxBuffer[BUFFER_SIZE];
size_t wifiRxIndex = 0;

void sendLine(const String& line) {
    if (responseStream != nullptr) responseStream->println(line);
    else if (wifiClient && wifiClient.connected()) wifiClient.println(line);
    else Serial.println(line);
}

// ─────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────
int getBatteryPercentage() {
    uint32_t vol = M5.getBatteryVoltage();
    if (vol <= 3300) return 0;
    if (vol >= 4150) return 100;
    return (int)((vol - 3300) * 100 / 850);
}

void pushHistory(const String& msg) {
    if (historyCount < 4) {
        messageHistory[historyCount++] = msg;
    } else {
        for (int i = 0; i < 3; i++) messageHistory[i] = messageHistory[i + 1];
        messageHistory[3] = msg;
    }
}

// ─────────────────────────────────────────────────────────────────
//  Dashboard render
// ─────────────────────────────────────────────────────────────────
void renderUI(bool fullRefresh = false) {
    canvas.clear();

    // 1. Header bar
    canvas.fillRect(0, 0, 960, 50, 15);
    canvas.setTextSize(3);
    canvas.setTextColor(0, 15);
    canvas.drawString("M5Paper  <--[USB/WiFi]-->  Android", 20, 14);

    M5.SHT30.UpdateData();
    float temp = M5.SHT30.GetTemperature();
    float hum  = M5.SHT30.GetRelHumidity();
    int batPct = getBatteryPercentage();
    char headerStats[64];
    snprintf(headerStats, sizeof(headerStats), "Bat: %d%%  |  %.1f C  |  %.0f%% RH", batPct, temp, hum);
    canvas.setTextSize(2);
    canvas.drawString(headerStats, 600, 18);

    // 2. Active message card (y=60..300)
    canvas.setTextColor(15, 0);
    canvas.drawRect(20, 60, 920, 240, 15);
    canvas.drawRect(21, 61, 918, 238, 15);
    canvas.fillRect(20, 60, 920, 30, 10);
    canvas.setTextColor(0, 10);
    canvas.setTextSize(2);
    canvas.drawString("  LATEST MESSAGE FROM ANDROID", 30, 68);

    canvas.setTextColor(15, 0);
    canvas.setTextSize(3);
    int textY = 104, lineStart = 0, len = currentMessage.length();
    while (lineStart < len && textY <= 280) {
        int lineEnd = lineStart + 42;
        if (lineEnd > len) lineEnd = len;
        int nlPos = currentMessage.indexOf('\n', lineStart);
        if (nlPos != -1 && nlPos < lineEnd) {
            lineEnd = nlPos;
        } else if (lineEnd < len) {
            int sp = currentMessage.lastIndexOf(' ', lineEnd);
            if (sp > lineStart) lineEnd = sp;
        }
        String sub = currentMessage.substring(lineStart, lineEnd);
        sub.trim();
        if (sub.length() > 0) { canvas.drawString(sub.c_str(), 36, textY); textY += 36; }
        lineStart = (nlPos != -1 && nlPos == lineEnd) ? lineEnd + 1 : lineEnd;
        if (lineStart < len && currentMessage.charAt(lineStart) == ' ') lineStart++;
    }

    // 3. History box – left (y=310..460)
    canvas.drawRect(20, 310, 450, 150, 15);
    canvas.fillRect(20, 310, 450, 26, 5);
    canvas.setTextSize(2);
    canvas.setTextColor(15, 5);
    canvas.drawString("  RECENT LOG", 25, 316);
    canvas.setTextColor(15, 0);
    int histY = 344;
    for (int i = 0; i < historyCount; i++) {
        String item = String(i + 1) + ". " + messageHistory[i];
        if (item.length() > 36) item = item.substring(0, 33) + "...";
        canvas.drawString(item.c_str(), 30, histY);
        histY += 26;
    }
    if (historyCount == 0) canvas.drawString("(empty)", 30, histY);

    // 4. Telemetry box – right (y=310..460)
    canvas.drawRect(490, 310, 450, 150, 15);
    canvas.fillRect(490, 310, 450, 26, 5);
    canvas.setTextColor(15, 5);
    canvas.drawString("  DEVICE & TELEMETRY", 495, 316);
    canvas.setTextColor(15, 0);
    canvas.setTextSize(2);
    char buf[64];
    snprintf(buf, sizeof(buf), "Bat: %d mV (%d%%)", M5.getBatteryVoltage(), batPct);
    canvas.drawString(buf, 500, 346);
    snprintf(buf, sizeof(buf), "Uptime: %lu s", millis() / 1000);
    canvas.drawString(buf, 500, 372);
    snprintf(buf, sizeof(buf), "Touch: %s", lastTouchInfo.c_str());
    canvas.drawString(buf, 500, 398);
    canvas.drawString("USB: 2 Mbps | Wi-Fi: TCP 3333", 500, 424);

    updateCounter++;
    if (fullRefresh || (updateCounter % 15 == 0)) canvas.pushCanvas(0, 0, UPDATE_MODE_GC16);
    else                                           canvas.pushCanvas(0, 0, UPDATE_MODE_DU4);
}

// ─────────────────────────────────────────────────────────────────
//  Compose box overlay + background restore
// ─────────────────────────────────────────────────────────────────
// Draws the compose box on the canvas (no push).
void drawComposeBox() {
    canvas.fillRect(0, COMPOSE_Y, 960, COMPOSE_H, 0);
    canvas.drawRect(0, COMPOSE_Y,     960, COMPOSE_H,     15);
    canvas.drawRect(1, COMPOSE_Y + 1, 958, COMPOSE_H - 2, 15);
    canvas.setTextSize(2);
    canvas.setTextColor(15, 0);
    // ~14 px per char at size 2 → show the last 64 chars so the cursor stays visible
    String shown = composeText;
    if (shown.length() > 64) shown = "..." + shown.substring(shown.length() - 61);
    shown += "|";
    canvas.drawString(shown.c_str(), 12, COMPOSE_Y + (COMPOSE_H - 16) / 2);
}

// Redraw what is under the compose box (screenshot if active, else dashboard).
void drawBackground(bool fullRefresh) {
    if (isShowingScreenshot && imgBuffer != nullptr && lastImgSize > 0) {
        canvas.clear();
        if (canvas.drawJpg(imgBuffer, lastImgSize, 0, 0)) {
            canvas.pushCanvas(0, 0, fullRefresh ? UPDATE_MODE_GC16 : UPDATE_MODE_DU4);
            return;
        }
    }
    renderUI(fullRefresh);
}

void showCompose(const String& text) {
    composeText    = text;
    composeVisible = true;
    drawComposeBox();
    canvas.pushCanvas(0, 0, UPDATE_MODE_DU4);
}

void hideCompose() {
    composeVisible = false;
    composeText    = "";
    drawBackground(true);
}

// ─────────────────────────────────────────────────────────────────
//  Process an incoming command line from Android
// ─────────────────────────────────────────────────────────────────
void processCommand(const String& cmd, Stream& source) {
    responseStream = &source;
    if (cmd.startsWith("WIFI_CONFIG:")) {
        String payload = cmd.substring(12);
        int firstSeparator = payload.indexOf(':');
        int secondSeparator = firstSeparator < 0 ? -1 : payload.indexOf(':', firstSeparator + 1);
        int ssidLength = firstSeparator < 0 ? -1 : payload.substring(0, firstSeparator).toInt();
        int passwordLength = secondSeparator < 0 ? -1 : payload.substring(firstSeparator + 1, secondSeparator).toInt();
        int credentialsStart = secondSeparator + 1;
        if (ssidLength > 0 && ssidLength <= 32 && passwordLength >= 0 && passwordLength <= 63 &&
            payload.length() == credentialsStart + ssidLength + passwordLength) {
            String ssid = payload.substring(credentialsStart, credentialsStart + ssidLength);
            String password = payload.substring(credentialsStart + ssidLength);
            preferences.putString("ssid", ssid);
            preferences.putString("password", password);
            wifiClient.stop();
            if (wifiServerStarted) wifiServer.end();
            if (discoveryStarted) discoveryUdp.stop();
            wifiServerStarted = false;
            discoveryStarted = false;
            WiFi.mode(WIFI_STA);
            WiFi.disconnect();
            WiFi.begin(ssid.c_str(), password.c_str());
            sendLine("ACK:WIFI_CONFIG");
        } else {
            sendLine("ERR:WIFI_CONFIG");
        }
    }
    else if (cmd.equalsIgnoreCase("WIFI_STOP")) {
        sendLine("ACK:WIFI_STOP");
        wifiStopPending = true;
    }
    else if (cmd.startsWith("MSG:")) {
        isShowingScreenshot = false;
        String msg = cmd.substring(4);
        if (currentMessage.length() > 0 &&
            currentMessage != "Ready for Android connection.\nConnect USB cable and tap 'Connect'.") {
            pushHistory(currentMessage);
        }
        currentMessage = msg;
        renderUI(false);
        sendLine("ACK:MSG");
    }
    else if (cmd.startsWith("COMPOSE:")) {
        showCompose(cmd.substring(8));
        sendLine("ACK:COMPOSE");
    }
    else if (cmd.equalsIgnoreCase("COMPOSE_CLEAR")) {
        hideCompose();
        sendLine("ACK:COMPOSE_CLEAR");
    }
    else if (cmd.equalsIgnoreCase("CLEAR")) {
        isShowingScreenshot = false;
        if (currentMessage.length() > 0) pushHistory(currentMessage);
        currentMessage = "(Screen cleared by Android)";
        renderUI(false);
        sendLine("ACK:CLEAR");
    }
    else if (cmd.equalsIgnoreCase("PING")) {
        sendLine("PONG");
    }
    else if (cmd.equalsIgnoreCase("STATUS?")) {
        M5.SHT30.UpdateData();
        float temp = M5.SHT30.GetTemperature();
        float hum  = M5.SHT30.GetRelHumidity();
        uint32_t bat_mv  = M5.getBatteryVoltage();
        int      bat_pct = getBatteryPercentage();
        unsigned long uptime = millis() / 1000;
        char resp[128];
        snprintf(resp, sizeof(resp),
                 "STATUS:bat_mv=%u,bat_pct=%d,temp=%.1f,hum=%.1f,uptime=%lu",
                 bat_mv, bat_pct, temp, hum, uptime);
        sendLine(resp);
    }
    else if (cmd.startsWith("BUZZ")) {
        sendLine("ACK:BUZZ");
    }
    else if (cmd.equalsIgnoreCase("REFRESH")) {
        isShowingScreenshot = false;
        renderUI(true);
        sendLine("ACK:REFRESH");
    }
    else if (cmd.equalsIgnoreCase("DASHBOARD")) {
        isShowingScreenshot = false;
        composeVisible = false;
        renderUI(true);
        sendLine("ACK:DASHBOARD");
    }
    else if (cmd.startsWith("IMG_BEGIN:")) {
        imgExpectedSize = cmd.substring(10).toInt();
        imgBufferPos = 0;
        lastImgSize  = 0;
        if (imgBuffer != nullptr && imgExpectedSize > 0 && imgExpectedSize <= MAX_IMG_SIZE) {
            sendLine("ACK:IMG_BEGIN");
        } else {
            sendLine("ERR:IMG_SIZE");
        }
    }
    else if (cmd.startsWith("IMG_CHUNK:")) {
        size_t chunkLen = cmd.substring(10).toInt();
        if (imgBuffer != nullptr && (imgBufferPos + chunkLen) <= MAX_IMG_SIZE) {
            size_t bytesRead = 0;
            unsigned long t0 = millis();
            while (bytesRead < chunkLen && (millis() - t0 < 4000)) {
                if (source.available()) {
                    size_t n = source.readBytes(
                        (char*)(imgBuffer + imgBufferPos + bytesRead), chunkLen - bytesRead);
                    bytesRead += n;
                    t0 = millis();
                } else {
                    yield();
                }
            }
            if (bytesRead == chunkLen) {
                imgBufferPos += chunkLen;
                sendLine("ACK:CHUNK");
                if (imgBufferPos >= imgExpectedSize) {
                    isShowingScreenshot = true;
                    canvas.clear();
                    bool ok = canvas.drawJpg(imgBuffer, imgExpectedSize, 0, 0);
                    if (ok) {
                        lastImgSize = imgExpectedSize;
                        if (composeVisible) drawComposeBox();   // keep the box on top of the new frame
                        canvas.pushCanvas(0, 0, UPDATE_MODE_GC16);
                        sendLine("ACK:IMG_DONE");
                    } else { sendLine("ERR:JPG_DECODE"); }
                    imgExpectedSize = 0;
                    imgBufferPos    = 0;
                }
            } else {
                sendLine("ERR:CHUNK_TIMEOUT:got=" + String(bytesRead) + ":expected=" + String(chunkLen));
                imgExpectedSize = 0;
                imgBufferPos    = 0;
            }
        } else {
            sendLine("ERR:CHUNK_OVERFLOW");
            imgExpectedSize = 0;
            imgBufferPos    = 0;
        }
    }
    else if (cmd.equalsIgnoreCase("IMG_ABORT")) {
        imgExpectedSize = 0;
        imgBufferPos    = 0;
        sendLine("ACK:IMG_ABORT");
    }
    else {
        sendLine("ERR:UNKNOWN_CMD:" + cmd);
    }
    responseStream = nullptr;
}

// ─────────────────────────────────────────────────────────────────
//  Setup
// ─────────────────────────────────────────────────────────────────
void setup() {
    M5.begin();
    M5.EPD.SetRotation(0);   // 960×540 landscape
    M5.TP.SetRotation(0);    // match touch to display
    M5.EPD.Clear(true);
    M5.RTC.begin();
    M5.SHT30.Begin();

    Serial.setRxBufferSize(16384);
    Serial.begin(SERIAL_BAUD_RATE);
    Serial.setTimeout(1500);
    preferences.begin("m5paper", false);

    String savedSsid = preferences.getString("ssid", "");
    String savedPassword = preferences.getString("password", "");
    if (savedSsid.length() > 0) {
        WiFi.mode(WIFI_STA);
        WiFi.begin(savedSsid.c_str(), savedPassword.c_str());
    }

    canvas.createCanvas(960, 540);

    imgBuffer = (uint8_t*)ps_malloc(MAX_IMG_SIZE);
    if (!imgBuffer) imgBuffer = (uint8_t*)malloc(MAX_IMG_SIZE);

    renderUI(true);
    delay(200);
    Serial.println("READY:M5Paper v1.0");
}

// ─────────────────────────────────────────────────────────────────
//  Main loop
// ─────────────────────────────────────────────────────────────────
void loop() {
    if (WiFi.status() == WL_CONNECTED && !wifiServerStarted) {
        wifiServer.begin();
        wifiServer.setNoDelay(true);
        wifiServerStarted = true;
        discoveryStarted = discoveryUdp.begin(WIFI_DISCOVERY_PORT);
        sendLine("WIFI:READY:" + WiFi.localIP().toString());
    }
    if (discoveryStarted) {
        int packetSize = discoveryUdp.parsePacket();
        if (packetSize > 0) {
            char query[32];
            int bytesRead = discoveryUdp.read(query, sizeof(query) - 1);
            if (bytesRead >= 0) {
                query[bytesRead] = '\0';
                if (String(query) == "M5PAPER_DISCOVER") {
                    String response = "M5PAPER:" + WiFi.localIP().toString() + ":" + String(WIFI_PORT);
                    discoveryUdp.beginPacket(discoveryUdp.remoteIP(), discoveryUdp.remotePort());
                    discoveryUdp.write((const uint8_t*)response.c_str(), response.length());
                    discoveryUdp.endPacket();
                }
            }
        }
    }
    if (wifiServerStarted && (!wifiClient || !wifiClient.connected())) {
        wifiClient = wifiServer.available();
        if (wifiClient) {
            wifiClient.setTimeout(1500);
            wifiClient.setNoDelay(true);
            sendLine("READY:M5Paper v1.0");
        }
    }

    auto readCommands = [](Stream& source, char* buffer, size_t& index) {
        while (source.available()) {
            char c = (char)source.read();
            if (c == '\r') continue;
            if (c == '\n') {
                if (index > 0) {
                    buffer[index] = '\0';
                    String line = String(buffer);
                    line.trim();
                    index = 0;
                    if (line.length() > 0) processCommand(line, source);
                }
            } else if (index < BUFFER_SIZE - 1) {
                buffer[index++] = c;
            }
        }
    };
    readCommands(Serial, rxBuffer, rxIndex);
    if (wifiClient && wifiClient.connected()) {
        readCommands(wifiClient, wifiRxBuffer, wifiRxIndex);
    }

    if (wifiStopPending) {
        wifiStopPending = false;
        wifiClient.stop();
        if (wifiServerStarted) wifiServer.end();
        if (discoveryStarted) discoveryUdp.stop();
        wifiServerStarted = false;
        discoveryStarted = false;
        WiFi.disconnect(true, false);
        WiFi.mode(WIFI_OFF);
    }

    // While an image is being received we still poll the touch panel (otherwise taps
    // made during the continuous screenshot stream are lost); buttons wait.
    bool transferring = (imgExpectedSize > 0 && imgBufferPos < imgExpectedSize);

    M5.update();

    // 2. Touch events
    if (M5.TP.available()) {
        M5.TP.update();
        if (!M5.TP.isFingerUp()) {
            tp_finger_t finger = M5.TP.readFinger(0);

            if (!wasFingerDown || (millis() - lastTouchTime > 180)) {
                lastTouchTime = millis();
                wasFingerDown = true;

                int tx = (int)finger.x;
                int ty = (int)finger.y;
                if (tx < 0)    tx = 0;
                if (tx > 959)  tx = 959;
                if (ty < 0)    ty = 0;
                if (ty > 539)  ty = 539;

                char touchStr[64];
                snprintf(touchStr, sizeof(touchStr),
                         "TOUCH:x=%d,y=%d,size=%d,scr=%d", tx, ty, finger.size, isShowingScreenshot ? 1 : 0);
                sendLine(touchStr);
                lastTouchInfo = String("X:") + tx + " Y:" + ty;
                if (!isShowingScreenshot && !transferring) renderUI(false);
            }
        } else {
            wasFingerDown = false;
        }
        M5.TP.flush();
    }

    if (transferring) { yield(); return; }

    // 3. Physical buttons (wheel switch)
    static bool longPressHandled = false;
    if (M5.BtnP.isPressed()) {
        if (!longPressHandled && M5.BtnP.pressedFor(1000)) {
            longPressHandled = true;
            // Long press: return to dashboard
            isShowingScreenshot = false;
            composeVisible = false;
            renderUI(true);
            sendLine("EVENT:DASHBOARD");
        }
    } else {
        if (M5.BtnP.wasReleased() && !longPressHandled) {
            sendLine("EVENT:BTN_PUSH");
            lastTouchInfo = "Wheel Click";
        }
        longPressHandled = false;
    }

    if (M5.BtnL.wasPressed()) {
        sendLine("EVENT:BTN_UP");
        lastTouchInfo = "Key UP";
        if (!isShowingScreenshot) renderUI(false);
    }

    if (M5.BtnR.wasPressed()) {
        sendLine("EVENT:BTN_DOWN");
        lastTouchInfo = "Key DOWN";
        if (!isShowingScreenshot) renderUI(false);
    }

    delay(2);
}
