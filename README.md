# M5Paper & Android USB Companion

Questo progetto include due applicazioni pronte all'uso che comunicano in tempo reale via cavo **USB (OTG)** a **115200 baud**:
1. **M5Paper Firmware** (ESP32 + E-ink 4.7" 960x540 + sensori SHT30 e Touch)
2. **Android App** (Kotlin + Jetpack Compose con driver USB-Serial per CP2104 / CH9102 / FTDI)

---

## 📐 Architettura di Comunicazione

```
+--------------------+        Cavo USB-C OTG        +--------------------+
|                    | <==========================> |                    |
|    Smartphone      |     (USB Host / Serial)      |      M5Paper       |
|    Android         |                              |   (ESP32 + E-ink)  |
|                    |   Baud: 2.000.000 (2 Mbps)   |                    |
+--------------------+                              +--------------------+
   [Jetpack Compose]                                  [M5EPD Canvas]
   - Invia messaggi                                   - Disegna a schermo
   - Richiede telemetria                              - Riporta Batteria/SHT30
   - Riceve touch/eventi                              - Riporta Touch/Tasti
```

### Protocollo Seriale (Line-based ASCII @ 2.000.000 baud / 2 Mbps)

Ogni comando termina con il carattere newline `\n`.

| Da | Comando / Formato | Descrizione |
|---|---|---|
| **Android $\to$ M5Paper** | `MSG:<testo>` | Mostra il messaggio nella scheda principale e aggiorna lo storico |
| **Android $\to$ M5Paper** | `CLEAR` | Cancella il messaggio attivo |
| **Android $\to$ M5Paper** | `PING` | Invia un ping di verifica (risponde `PONG`) |
| **Android $\to$ M5Paper** | `STATUS?` | Richiede la telemetria del dispositivo |
| **Android $\to$ M5Paper** | `REFRESH` | Forza un refresh completo (GC16) per rimuovere ghosting |
| **Android $\to$ M5Paper** | `DASHBOARD` | Ritorna alla schermata principale uscendo dallo screenshot |
| **Android $\to$ M5Paper** | `IMG_BEGIN:<bytes>` | Avvia il trasferimento dello screenshot JPEG (max 512 KB) |
| **Android $\to$ M5Paper** | `IMG_CHUNK:<bytes>` + dati | Invia un blocco di dati JPEG (2 KB) seguito dai byte binari |
| **Android $\to$ M5Paper** | `IMG_ABORT` | Annulla il trasferimento dello screenshot corrente |
| **M5Paper $\to$ Android** | `READY:M5Paper v1.0` | Inviato all'avvio dell'M5Paper |
| **M5Paper $\to$ Android** | `ACK:<CMD>` | Riscontro ricezione ed esecuzione comando (`ACK:IMG_BEGIN`, `ACK:CHUNK`, `ACK:IMG_DONE`) |
| **M5Paper $\to$ Android** | `PONG` | Risposta al ping |
| **M5Paper $\to$ Android** | `STATUS:bat_mv=X,bat_pct=Y,temp=Z,hum=W,uptime=U` | Telemetria completa (batteria, SHT30, uptime) |
| **M5Paper $\to$ Android** | `TOUCH:x=X,y=Y,size=S,scr=1/0` | Coordinate del tocco: se durante lo screenshot (`scr=1`), simula il tocco sullo smartphone e aggiorna lo schermo |
| **M5Paper $\to$ Android** | `EVENT:BTN_PUSH` | **Click rotellina**: richiede lo screenshot; **pressione prolungata (>1s)**: torna alla dashboard (`EVENT:DASHBOARD`) |
| **M5Paper $\to$ Android** | `EVENT:BTN_UP` | **Rotellina Su**: scorre la pagina verso l'alto (Scorri Pagina / Volume / D-Pad) |
| **M5Paper $\to$ Android** | `EVENT:BTN_DOWN` | **Rotellina Giù**: scorre la pagina verso il basso (Scorri Pagina / Volume / D-Pad) |
| **M5Paper $\to$ Android** | `EVENT:DASHBOARD` | Notifica il ritorno alla dashboard dell'M5Paper |

---

## 📁 Struttura della Cartella

```
m5paper-android-usb/
├── README.md
├── m5paper/                         # Progetto Firmware M5Paper
│   ├── platformio.ini               # Configurazione PlatformIO (ESP32 + PSRAM)
│   ├── src/
│   │   └── main.cpp                 # Codice sorgente completo
│   └── m5paper_usb_display/
│       └── m5paper_usb_display.ino  # Versione per Arduino IDE
└── android/                         # Progetto Android Studio (Gradle + Kotlin)
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/
            │   ├── xml/usb_device_filter.xml
            │   └── values/
            └── java/com/example/m5paperusb/
                ├── MainActivity.kt
                ├── UsbSerialController.kt
                └── ui/theme/
```

---

## 🛠️ 1. Installazione e Caricamento su M5Paper

### Opzione A: Con PlatformIO (Consigliata)
1. Apri la cartella `m5paper/` in VS Code con l'estensione **PlatformIO**.
2. Collega l'M5Paper al PC via cavo USB-C.
3. Clicca su **Build** (`pio run`) e poi **Upload** (`pio run --target upload`).
4. Le dipendenze (libreria `M5EPD`) vengono scaricate automaticamente.

### Opzione B: Con Arduino IDE
1. Apri `m5paper/m5paper_usb_display/m5paper_usb_display.ino` in Arduino IDE.
2. Nelle impostazioni Board Manager, installa il pacchetto **esp32** di Espressif.
3. Seleziona la scheda:
   - Scheda: `M5Stack-Fire` oppure `Generic ESP32 Dev Module`
   - Flash Size: `16MB (128Mb)`
   - Partition Scheme: `Default 16MB (Default with spiffs)`
   - PSRAM: `Enabled`
4. Nel Library Manager, cerca e installa la libreria ufficiale: **M5EPD** di M5Stack.
5. Clicca su **Upload**.

---

## 📱 2. Compilazione e Avvio dell'App Android

1. Apri la cartella `android/` in **Android Studio** (Hedgehog, Iguana, Jellyfish o superiore).
2. Attendi la sincronizzazione di Gradle (scaricherà Jetpack Compose e `usb-serial-for-android`).
3. Collega lo smartphone Android in modalità Debug USB o compila l'APK (`./gradlew assembleDebug`).
4. Installa ed avvia l'app sul telefono.

---

## 🔌 3. Collegamento Fisico (Cablaggio e Note OTG)

1. **Cavo USB OTG**:
   - Collega lo smartphone all'M5Paper tramite un cavo **USB-C $\to$ USB-C** oppure un adattatore **USB-A OTG** (collegato al telefono) + cavo standard USB-A $\to$ USB-C (verso l'M5Paper).
   - Lo smartphone deve fare da **USB Host** e alimentare/comunicare con il chip USB-UART dell'M5Paper (CP2104 o CH9102).
2. **Abilitazione OTG su Android**:
   - Su alcuni produttori (OnePlus, Oppo, Realme, Xiaomi, Vivo), l'alimentazione OTG va abilitata manualmente nelle impostazioni di Android:
     *Impostazioni $\to$ Impostazioni di sistema $\to$ Connessione OTG (Attiva)*.
3. **Permessi USB**:
   - All'apertura dell'app (o all'inserimento del cavo), Android mostrerà un popup:
     *"Consentire a M5Paper USB Companion di accedere al dispositivo USB?"*
   - Clicca su **Consenti** (puoi anche spuntare "Usa sempre per questo dispositivo").

---

## ✨ Funzionalità Incluse

- **Display E-ink Reattivo**: Aggiornamento parziale `UPDATE_MODE_DU4` per disegnare il testo istantaneamente senza sfarfallio nero/bianco; pulizia automatica periodica `UPDATE_MODE_GC16` contro il ghosting.
- **Riconoscimento Automatico Chip USB**: Supporta sia i chip **CP2104** (modelli M5Paper originali) sia **CH9102** (revisioni recenti) e FTDI.
- **Console Seriale Live**: Monitor bidirezionale con timestamp e colorazione automatica:
  - `[TX]` (azzurro): comandi inviati da Android
  - `[RX]` (verde): risposte, telemetria ed eventi da M5Paper
  - `[SYS]` (giallo): stato connessione e driver
- **📸 Screenshot Mirroring e Controllo Touch Remoto**: Premendo il tasto centrale (click rapido rotellina) su M5Paper, lo smartphone acquisisce automaticamente uno screenshot via `MediaProjection`, lo formatta per il display 960x540 (ruotato a pieno schermo o centrato) e lo invia su USB seriale a 2 Mbps. M5Paper visualizza lo screenshot a pieno schermo (`UPDATE_MODE_GC16`). Toccando lo schermo dell'M5Paper durante la visualizzazione dello screenshot, le coordinate vengono rimappate ed inviate allo smartphone, che simula il tocco via `RemoteTouchAccessibilityService` e aggiorna automaticamente la schermata. Tenendo premuta la rotellina per 1 secondo o premendo 'Home' dall'app si torna alla dashboard!
- **🎛️ Tasti Fisici Su e Giù**: Muovendo la rotellina dell'M5Paper verso l'alto (`EVENT:BTN_UP`) o verso il basso (`EVENT:BTN_DOWN`), viene azionato lo **Scorrimento Pagina (Scroll)** dello smartphone (default, ideale per scorrere pagine web, documenti ed e-book), oppure **Volume Su/Giù** o **Frecce Direzionali (D-Pad)**, configurabili tramite selettore nell'app.
