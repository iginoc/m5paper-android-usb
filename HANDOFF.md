# 📋 HANDOFF / Documento di Passaggio di Consegne

## 📌 Contesto e Obiettivo
Il progetto realizza una connessione USB ad alta velocità (fino a 2 Mbps) tra uno **smartphone Android** e un dispositivo **M5Stack M5Paper** (display E-ink 960x540 con touch). L'applicazione consente di visualizzare lo schermo dello smartphone in tempo reale sull'M5Paper (mirroring ottimizzato per E-ink) e di interagire bidirezionalmente:
- **Tocco remoto**: toccando il display dell'M5Paper si simula il tocco sullo smartphone via `AccessibilityService`.
- **Tasti fisici (rotellina)**: muovendo la rotellina su/giù o cliccandola, vengono inviati eventi all'app Android.

### Cosa si stava facendo al momento del blocco
In precedenza, i tasti fisici Su e Giù dell'M5Paper (`EVENT:BTN_UP` ed `EVENT:BTN_DOWN`) erano associati di default ai tasti fisici del volume dello smartphone (`AudioManager`). L'utente ha richiesto che i tasti Su e Giù venissero invece associati allo **scorrimento della pagina (scroll)** dello smartphone (utile ad esempio per lettura di pagine web, documenti ed ebook).
Durante questa modifica, la sessione precedente ha subito il blocco per limite di quota (quota limit), lasciando le modifiche parzialmente applicate e salvate nel commit `b66beea` (*"Salvataggio prima del cambio account"*).

---

## 🛠️ File Modificati

1. **`android/app/src/main/java/com/example/m5paperusb/RemoteTouchAccessibilityService.kt`**
   - Risolto fallback per le metriche display in caso di screenshot non ancora inizializzato.
   - Implementata simulazione di swipe gestuale per scorrimento pagina.
   - Aggiunta funzione `performHome(onComplete)` per l'invio nativo di `GLOBAL_ACTION_HOME` via AccessibilityService.
   - Aggiunte funzioni per l'input di testo remoto: `sendText()`, `appendText()`, `deleteLastChar()`, `performEnter()` con sincronizzazione su campo editabile e clipboard di sistema.

2. **`android/app/src/main/java/com/example/m5paperusb/ScreenshotManager.kt`**
   - Rimosso `ScreenshotFitMode`: screenshot sempre ruotato di 90° e allineato a destra.
   - Creata barra laterale dei pulsanti funzione a sinistra dello screenshot (riservando almeno `MIN_SIDEBAR_WIDTH = 90px`).
   - Disegnato il pulsante **Home** con sfondo a contrasto, bordo scuro e icona vettoriale a casetta orientata correttamente nella vista utente.
   - Aggiunto il pulsante **Refresh** (icona vettoriale freccia circolare di ricarica) posizionato subito sotto il pulsante Home.
   - Aggiunto il terzo pulsante **Tastiera** (icona vettoriale tastiera con stato attivo/inattivo ad alto contrasto) posizionato sotto Refresh.
   - Implementata la **tastiera virtuale su schermo** nella parte bassa (`KEYBOARD_HEIGHT_X = 220px`):
     - Lo screenshot dello smartphone **non si restringe** mai all'apertura della tastiera: mantiene le sue proporzioni e la sua scala intera, con la tastiera disegnata come overlay nella porzione inferiore.
     - Layout completo a 5 righe sempre visibile: riga cifre (1-0), righe QWERTY (Q-P, A-L, Z-M), tasto Shift (⇧), Backspace (⌫), virgola (,), punto (.), spazio e tasto Invia (↵).
     - Durante la digitazione dei singoli tasti **lo schermo non si aggiorna** (zero refresh/flicker E-ink); il refresh viene attivato unicamente alla pressione del tasto **INVIA ↵**.
   - Implementati `getHomeButtonBounds()`, `getRefreshButtonBounds()`, `getKeyboardButtonBounds()`, `isHomeButtonTouch()`, `isRefreshButtonTouch()`, `isKeyboardButtonTouch()`, `isKeyboardTouch()` e `handleKeyboardTouch()`.
   - Aggiornato `mapM5PaperTouchToPhone` per preservare la mappatura 1:1 dello screenshot ed escludere i tocchi nella zona della tastiera.

3. **`android/app/src/main/java/com/example/m5paperusb/MainActivity.kt`**
   - Rimosso selettore pulsanti formato.
   - Aggiunta intercettazione tocco Home (`isHomeButtonTouch` e `EVENT:HOME`), esecuzione di `performHome()` e refresh ritardato dello screenshot (450ms) per catturare la schermata Home di Android.
   - Aggiunta intercettazione tocco Refresh (`isRefreshButtonTouch` ed `EVENT:REFRESH`), che richiama `triggerScreenshotCapture()` (identico al click del pulsante centrale/rotellina dell'M5Paper).
   - Aggiunta intercettazione tocco Tastiera (`isKeyboardButtonTouch` ed `EVENT:KEYBOARD`) per attivare/disattivare la tastiera su schermo.
   - Aggiunta gestione della digitazione interattiva (`handleKeyboardTouch`).

4. **`README.md`**
   - Documentata la nuova barra laterale dei pulsanti funzione (Home, Refresh e Tastiera virtuale).

5. **Firmware M5Paper** (`m5paper/src/main.cpp` e `m5paper/m5paper_usb_display/m5paper_usb_display.ino`)
   - Ottimizzato debounce del tocco a 180ms per una digitazione fluida e reattiva sulla tastiera virtuale.
   - Firmware compilato e flashato con successo su M5Paper.

---

## 💡 Decisioni Prese

1. **Scorrimento tramite Swipe Gestuale invece di Azioni Globali / Nodi**:
   - `performGlobalAction` di Android non offre un'azione standard di scorrimento universale (il DPAD è disponibile solo da Android 13 in su e spesso non scorre le pagine web).
   - È stata utilizzata la simulazione gestuale con `GestureDescription.StrokeDescription` e `dispatchGesture` nell'area centrale dello schermo (tra il 35% e il 75% in altezza). Questo approccio garantisce la compatibilità con qualunque app (browser Chrome, Firefox, lettori Kindle, PDF, social media, app di notizie) e non tocca le barre di stato o di navigazione.
2. **Refresh asincrono con ritardo di stabilizzazione**:
   - Dopo l'esecuzione del gesto di scorrimento, viene applicato un delay di 400ms prima di richiamare `triggerScreenshotCapture()`. Questo permette al motore di rendering di Android e all'inerzia dello scroll di completare l'animazione, catturando un'immagine nitida e ferma.
3. **Mantenimento del selettore nell'interfaccia utente**:
   - Lo scorrimento pagina è l'azione predefinita, ma l'utente può comunque selezionare *Volume Su/Giù* o *Frecce D-Pad* tramite i chip di selezione nell'app Android, preservando la flessibilità.

---

## 🚧 Problemi Incontrati e Risolti

- **Interruzione per Quota API**: la sessione precedente si è chiusa improvvisamente per superamento limiti. È stato analizzato il log git (`b66beea`, `dc43a41`) ed è stato ripreso il lavoro senza perdita di codice.
- **Coordinate 0 nel calcolo dello Swipe**: se l'utente premeva la rotellina prima di avviare la cattura dello schermo, le dimensioni del display risultavano 0 e lo swipe falliva silenziosamente. Risolto leggendo le metriche reali del display dal contesto del servizio di accessibilità.
- **Mancata notifica se l'Accessibilità è spenta**: premendo i tasti senza aver autorizzato l'accessibilità, il comando falliva solo nei log. È stato aggiunto un Toast visivo esplicito per guidare l'utente ad attivare il servizio nelle Impostazioni.

---

## 🔮 Cosa Resta da Fare (Possibili Miglioramenti Futuri)

1. **Test su dispositivo fisico**:
   - Collegare l'M5Paper con cavo USB-C OTG allo smartphone.
   - Verificare la sensibilità e la distanza dello scorrimento nelle proprie app di lettura preferite.
2. **Regolazione della distanza di scorrimento (opzionale)**:
   - Se desiderato, aggiungere un cursore o impostazione nell'app per regolare la percentuale di scorrimento (es. mezza pagina vs pagina intera: `30%` - `60%` dell'altezza dello schermo).
3. **Push su repository Git remoto**:
   - I commit locali sono pronti (`d44af88` e `b66beea`), eseguire `git push` se si desidera aggiornare il server remoto.

---

## 🧪 Comandi e Test da Eseguire

### 1. Compilazione Firmware M5Paper
```bash
/home/igino/.platformio/penv/bin/pio run -d m5paper
```
Per effettuare il flash del firmware sull'M5Paper collegato:
```bash
/home/igino/.platformio/penv/bin/pio run -d m5paper -t upload
```

### 2. Compilazione e Installazione App Android
Compilazione APK di debug:
```bash
./android/gradlew --project-dir ./android assembleDebug
```
L'APK generato si trova in:
`android/app/build/outputs/apk/debug/app-debug.apk`

Installazione diretta via ADB su smartphone collegato:
```bash
./android/gradlew --project-dir ./android installDebug
```

### 3. Procedura di Test con Hardware
1. Aprire l'app **M5Paper USB Companion** sullo smartphone.
2. Concedere i permessi:
   - **Accesso USB** al popup di sistema.
   - **Cattura Schermo** (toccare *"Abilita Cattura Schermo"* e confermare il permesso MediaProjection).
   - **Accessibilità**: cliccare sul banner o chip *"Touch OFF"* per abilitare l'app in *Impostazioni > Accessibilità*.
3. Verificare che il selettore *"Tasti Su/Giù"* sia posizionato su **Scorri Pagina**.
4. Premere una volta la rotellina centrale su M5Paper per acquisire la prima schermata.
5. Aprire una pagina web o un testo lungo sullo smartphone e muovere la rotellina verso il basso / alto:
   - Lo smartphone deve scorrere la pagina.
   - L'M5Paper deve ricevere il nuovo screenshot aggiornato pochi istanti dopo.
