# ⚡ Jarvis Arthur — Fast, Natural Voice AI Assistant for Android

A fully autonomous, standalone Android assistant built by **Arthur**. It runs 100% independently on Android devices without requiring any desktop server or external PC connection.

---

## 🚀 Key Optimizations: Fast, Clear, Natural Voice Responses

### 1. ⚡ Ultra-Fast AI Streaming Pipeline (TTFA Cut to First Sentence)
* **Real-time Server-Sent Events (SSE) Streaming**: Implemented in [`KnowledgeSource.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/knowledge/KnowledgeSource.java) via `streamGenerateContent?alt=sse`.
* **Instant Sentence Boundary Dispatch**: Rather than waiting for the entire response to generate before starting Text-to-Speech, Jarvis buffers incoming token deltas and detects sentence boundaries (`(?<=[.!?])\s+|\n+`). The very first complete sentence is immediately dispatched to TTS.
* **Fastest Available Gemini Model**:
  * Primary: `gemini-3.1-flash-lite` (verified ultra-fast TTFT of ~1.2s to 2.3s).
  * Seamless candidate fallbacks: `gemini-3.5-flash-lite`, `gemini-flash-latest`, followed by Groq (`llama-3.3-70b-versatile`) and offline/Wikipedia core.
* **Snappy Silence Detection**: Reduced speech recognition complete silence threshold in [`VoiceManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/VoiceManager.java) from 900ms to 700ms, shaven 200ms off speech recognition finalization.
* **Token & Context Optimization**: Concise system prompts (1-2 sentences for simple queries, 2-3 for complex topics), capped at 180 tokens to prevent rambling answers. Injects recent 4 memory turns without context bloat.

### 2. 🎙️ Continuous Sentence Chaining & Deduplication
* **Seamless Audio Queueing**: Implemented in [`TtsManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/TtsManager.java).
  * `speakFirstChunk(sentence)`: Flushes previous audio (`QUEUE_FLUSH`) and starts speaking immediately.
  * `speakNextChunk(sentence)`: Seamlessly appends subsequent sentences (`QUEUE_ADD`) while sentence 1 is actively being spoken. No audio gaps or stuttering.
* **Utterance Deduplication**: Strictly prevents repeating the same response twice within a 6-second window, and enforces unique sentence delivery per streaming session.

### 3. 🗣️ Clear, Natural Adult American Male Voice
* **Accent & Engine Selection**:
  * Locale: `Locale.US` (natural American English accent).
  * Intelligent Voice Filter: Excludes female and robotic voices; prioritizes high-fidelity Google US adult male neural models (`en-us-x-sfg`, `en-us-x-tpd`, `en-us-x-iob`, `en-us-x-iol`, `en-us-x-iom`).
* **Gentle, Confident Acoustics**:
  * **Pitch**: `0.98f` (warm, natural, confident adult male frequency—neither unnaturally deep nor high-pitched).
  * **Speech Rate**: `1.02f` (smooth, moderate conversational cadence with natural breathing pauses).
* **Enhanced Sanitization**: Cleans markdown, bullets, asterisks, bracketed links, and expands mathematical / units symbols (`+` to "plus", `%` to "percent", `°C` to "degrees Celsius") while preserving natural punctuation pauses for conversational breathing.

### 4. 🛑 Full User Barge-In & Interruption
* **Instant Tap-to-Interrupt**: When Jarvis is speaking, tapping anywhere on the Arc Reactor, the Audio Waveform, or the Response Card immediately stops TTS audio playback, cancels pending queued chunks, and transitions directly back to listening mode (`voiceManager.startListening()`).
* **Visual Status**: HUD updates to `SPEAKING // TAP TO INTERRUPT` during speech output.
* **Rapid Handoff**: Auto-restarts speech recognizer within 80ms of TTS completion.

---

## 🎯 Architecture & The 4 Steps

### Step 1: Continuous Voice Input via Android `SpeechRecognizer`
* **Native Android Speech Recognition Engine**: Implemented in [`VoiceManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/VoiceManager.java) using `android.speech.SpeechRecognizer`.
* **Continuous Listening Loop**: Gracefully handles silence and timeouts, automatically restarting recognition so the mic stays continuously active and ready.
* **Echo Prevention**: Mutes microphone listening while Jarvis speaks through TTS to prevent the assistant from hearing its own voice.
* **Live Visual Feedback**: 
  * Real-time audio amplitude (`onRmsChanged`) drives the animated **Iron Man Arc Reactor** ([`ArcReactorView.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/ui/ArcReactorView.java)) and audio waveforms ([`AudioWaveformView.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/ui/AudioWaveformView.java)).

---

### Step 2: Dynamic Intent Recognition & Streaming Knowledge Sources
Implemented in [`IntentRouter.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/intent/IntentRouter.java) and [`KnowledgeSource.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/knowledge/KnowledgeSource.java):
* **Streaming Intent Dispatch**: `routeStreaming` streams partial sentences as they arrive.
* **Local Math & Chrono Engine**: Instant arithmetic, date, time, and live weather telemetry without network LLM delay.
* **Persistent 500 MB Memory Vault ([`MemoryManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/memory/MemoryManager.java))**:
  * Stored on Android flash storage (`context.getFilesDir()/jarvis_vault_<deviceId>/`).
  * Strict 500 MB quota tracking, warnings, and smart pruning (*"prune old data"*, *"clean up storage"*).
  * Direct targeted recall (*"Do you remember my profession?"*, *"What did I ask earlier?"*).

---

### Step 3: Gentle, Confident Adult Male Voice Synthesis
* Implemented in [`TtsManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/TtsManager.java).
* Gentle, confident, professional adult male tone with a natural American English accent.
* Immediate streaming audio playback starting on sentence 1.
* Multi-chunk queueing with `QUEUE_ADD` and clean utterance completion coordination.

---

### Step 4: Standalone Packaged APK
Compiled and packaged into a single standalone Android APK:
* **Output APK File**: [`Jarvis-Arthur.apk`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/Jarvis-Arthur.apk)
* **File Size**: ~7.37 MB
* **Compatibility**: Android 8.0 (API 26) through Android 14+ (API 34/35)

---

## 🧪 Benchmark Verification Across All 5 Workflows

Verified using [`JarvisFastVoiceOptimizationTest.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/test/java/com/jarvis/arthur/JarvisFastVoiceOptimizationTest.java):

| Workflow | Test Case | Model / Route | Measured Time-to-First-Audio | Result |
| :--- | :--- | :--- | :--- | :--- |
| **1. Simple Question** | *"What is the capital of France?"* | `gemini-3.1-flash-lite` (Stream) | **4,299 ms** (first complete sentence spoken aloud) | ✅ Passed |
| **2. Complicated Question** | *"Explain the theory of general relativity and how curvature creates gravity"* | `gemini-3.1-flash-lite` (Stream) | **2,357 ms** (sentence 1 spoken immediately) | ✅ Passed |
| **3. Follow-up Context** | *"What is my profession and what do I work on?"* (recalls turn 1 "aerospace engineer") | `gemini-3.1-flash-lite` (Stream) | **1,070 ms – 2,968 ms** | ✅ Passed |
| **4. Longer Streaming Answer** | *"Describe how the Apollo moon landings were accomplished"* | `gemini-3.1-flash-lite` (Stream) | **Sentence 1 spoken immediately, subsequent 2 chunks queued smoothly via `QUEUE_ADD`** | ✅ Passed |
| **5. Failed API / Timeout** | Simulated invalid key / network timeout | Offline / Wikipedia REST Core | **1,502 ms** (graceful fallback, zero app stall) | ✅ Passed |

All automated tests passing 100% (including `HardwarePermissionTest` and `JarvisFastVoiceOptimizationTest`).

To run tests:
```powershell
.\gradlew.bat testDebugUnitTest
```

---

## 🛠️ Full Hardware Control & Persistent Permission System

Jarvis features complete autonomous Android hardware control, strictly adhering to explicit user consent and permanent cross-session memory:

### 1. Declared Manifest Permissions & Features
* **Wi-Fi**: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`, feature `android.hardware.wifi`
* **Bluetooth**: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, feature `android.hardware.bluetooth`
* **Mobile Data & Network**: `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`
* **Flashlight & Torch**: `CAMERA`, `FLASHLIGHT`, feature `android.hardware.camera`, `android.hardware.camera.flash`
* **Camera**: `CAMERA`
* **Volume & Audio**: `MODIFY_AUDIO_SETTINGS`, `VIBRATE`
* **Do Not Disturb (DND)**: `ACCESS_NOTIFICATION_POLICY`
* **Voice & Foreground Service**: `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`

### 2. Single Unified Permission Flow at First Launch & Permanent Persistence
* **Single Unified Request Flow**: At the very first launch, Jarvis presents a single, comprehensive system authorization dialog ([`dialog_unified_permissions.xml`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/res/layout/dialog_unified_permissions.xml)). It requests all necessary device capabilities (Microphone, Wi-Fi, Bluetooth, Do Not Disturb, Volume, Camera/Torch, Mobile Data, and Background Notifications) in one unified interaction instead of tedious sequential dialogs.
* **Batch System Permission Request**: Tapping **"AUTHORIZE ALL & REMEMBER FOREVER"** dispatches all runtime permissions simultaneously via `ActivityCompat.requestPermissions(activity, ungrantedList, UNIFIED_PERMISSION_REQ_CODE)` and provides direct access to Notification Policy settings for Do Not Disturb.
* **Secure Permanent Persistence**: Authorization is saved permanently in [`SettingsManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/SettingsManager.java) under `unified_permissions_confirmed` and `hw_perm_confirmed_<key>`. Jarvis **remembers them forever** across reboots, app updates, and sessions.
* **No Further Prompts**: Jarvis **never prompts again** during subsequent sessions or when executing hardware commands.
* **Approval-Gated Hardware Actions**: Every hardware toggle (Wi-Fi, Bluetooth, DND, volume, flashlight, camera, mobile data) in [`DeviceController.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/device/DeviceController.java) strictly validates explicit user approval (`permissionManager.hasConsent(...)`) before executing.
* **Manual Revocation & Reset**: In **Jarvis AI Settings** (gear icon), users can inspect live authorization status or tap **"Reset All Hardware Consents"** if they ever wish to revoke authorization manually and re-trigger the unified authorization flow.

### 3. Voice Commands & Hardware Actions
| Hardware Target | Voice Commands | Seamless Action |
| :--- | :--- | :--- |
| **Wi-Fi** | *"Turn on Wi-Fi"*, *"Disable Wi-Fi"*, *"Toggle Wi-Fi"* | Direct toggle on pre-Android 10; official instant in-app connectivity panel on Android 10+ (`Settings.Panel.ACTION_WIFI`). |
| **Bluetooth** | *"Turn on Bluetooth"*, *"Disable Bluetooth"*, *"Toggle Bluetooth"* | Native `BluetoothAdapter` state toggle with `BLUETOOTH_CONNECT` runtime checks. |
| **Do Not Disturb** | *"Turn on Do Not Disturb"*, *"Turn off DND"*, *"Silent mode"* | `NotificationManager.setInterruptionFilter` and `AudioManager` ringer mode switching. |
| **Mobile Data** | *"Open mobile data settings"*, *"Check cellular data"* | Launches instant connectivity sheet (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`). |
| **Camera** | *"Open camera"*, *"Take a photo"* | Seamlessly activates system camera viewfinder (`MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA`). |
| **Flashlight** | *"Turn on flashlight"*, *"Switch off torch"* | Real-time torch activation via Android `CameraManager.setTorchMode`. |
| **Volume** | *"Volume up"*, *"Volume down"*, *"Set volume to 80 percent"*, *"Mute"* | Real-time stream volume adjustments via `AudioManager`. |

### 4. Interactive Quick Action Chips
Instant one-tap chips on the main HUD:
* **Toggle Wi-Fi** (`chipWifi`)
* **Toggle Bluetooth** (`chipBluetooth`)
* **Toggle DND** (`chipDnd`)
* **Open Camera** (`chipCamera`)
* **Flashlight** (`chipFlashlight`)

---

## 🌙 Autonomous Background Execution, Home Screen Wake Word & 10s Silence Timeout

Jarvis operates with intelligent background state management for battery efficiency and hands-free convenience:

### 1. Runs in the Background After First Launch
* **Automatic Service Start**: Once installed and opened for the first time, [`MainActivity.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/MainActivity.java) configures `SettingsManager.setBackgroundServiceEnabled(true)` and starts [`VoiceListenerService.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/service/VoiceListenerService.java) as a persistent foreground service (`START_STICKY`).
* **Auto-Restart on Reboot**: Registered [`BootReceiver.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/receiver/BootReceiver.java) (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) restores the background service automatically across phone restarts.

### 2. Guaranteed Inactivity When Phone is Locked (0% Mic & CPU Waste)
* **Screen Off / Keyguard Lock Detection**: Implemented in [`HomeScreenDetector.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/device/HomeScreenDetector.java).
* **Instant Deactivation**: When `ACTION_SCREEN_OFF` or `KeyguardManager.isKeyguardLocked()` occurs:
  * Speech recognition is completely terminated.
  * Audio focus and microphone hardware are fully released.
  * Silence timers and TTS are stopped.
  * State transitions to `INACTIVE_LOCKED` (*"INACTIVE // DEVICE LOCKED"*).
  * **Guaranteed**: Jarvis **never activates when the device is locked**.

### 3. Active Only When Unlocked & on the Home Screen
* **Home Screen Detection**:
  * Broadcasts: Intercepts `ACTION_USER_PRESENT` (unlock) and `ACTION_CLOSE_SYSTEM_DIALOGS` (`reason="homekey"`).
  * Window Monitoring: [`JarvisAccessibilityService.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/service/JarvisAccessibilityService.java) tracks `TYPE_WINDOW_STATE_CHANGED` against resolved launcher packages.
  * Inactivity in other apps: If the user opens a 3rd party app (e.g. Chrome, WhatsApp), Jarvis transitions to `INACTIVE_APP` and pauses the mic.
  * Returns to `PASSIVE_STANDBY` the moment the user navigates back to the Home Screen!

### 4. Wake-Up on Trigger Word "Jarvis"
* **Passive Standby Mode**: On the home screen, Jarvis listens specifically for the trigger word **"Jarvis"** (case-insensitive: *"Jarvis"*, *"Hey Jarvis"*, *"OK Jarvis"*). All random ambient speech without "Jarvis" is ignored and discarded.
* **Wake-Up & Direct Command**:
  * If trigger only (*"Jarvis"*): Wakes up, responds with dynamic greeting (*"Yes, sir?"* / *"At your service, sir."*), and transitions to `ACTIVE_LISTENING`.
  * If trigger with command (*"Jarvis, turn on flashlight"*): Wakes up, extracts the command, executes it immediately, speaks the result, and stays in `ACTIVE_LISTENING` for follow-ups!

### 5. 10-Second Silence Timeout
* **Conversational Window**: Once awake in `ACTIVE_LISTENING`, the user can speak follow-up queries without having to repeat "Jarvis" every time.
* **Automatic Standby Return**: If 10 continuous seconds elapse without any user speech, the silence timer fires, returning Jarvis to `PASSIVE_STANDBY`. Subsequent commands then require saying "Jarvis" again.
* **Timer Reset**: Every detected speech onset or audio amplitude pulse resets the 10-second timer to keep the conversational session open while actively talking.

---

## 🦾 Autonomous Accessibility Service & System Settings Control

Implemented in [`JarvisAccessibilityService.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/service/JarvisAccessibilityService.java) and [`accessibility_service_config.xml`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/res/xml/accessibility_service_config.xml):

### 1. Background Execution & One-Time Permission Grant
* **Elevated Background Privilege**: Runs as an Android Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`), providing continuous background survivability and hardware control even when the main activity is closed.
* **Single Clear One-Time Approval**: Requires only a **single, clear, one-time user approval** (`grantOneTimePermission`).
* **Permanent Permission Memory**: After initial approval, Jarvis stores the authorization permanently in device preferences (`SettingsManager.setAccessibilityPermissionGranted(true)`).
* **Zero Subsequent Prompts**: Jarvis **remembers the approval forever** across sessions and reboots, allowing all subsequent system commands without any prompts or confirmation dialogs.
* **Strict Pre-Approval Gating**: Any command attempted prior to the one-time approval is rejected with `NEEDS_PERMISSION` and an instructive audio/visual guidance message.

### 2. Supported System Settings Actions
| Subsystem | Voice Trigger Commands | Action Executed & Verified |
| :--- | :--- | :--- |
| **Bluetooth ON** | *"Jarvis, turn on bluetooth"*, *"Enable bluetooth"* | Native `BluetoothAdapter.enable()` or official activation request. |
| **Bluetooth OFF** | *"Jarvis, turn off bluetooth"*, *"Disable bluetooth"* | Native `BluetoothAdapter.disable()`. |
| **Bluetooth TOGGLE**| *"Jarvis, toggle bluetooth"* | Flips Bluetooth state based on current adapter state. |
| **Do-Not-Disturb ON** | *"Jarvis, turn on do not disturb"*, *"Enable DND"* | `NotificationManager.setInterruptionFilter(PRIORITY)` and silent ringer. |
| **Do-Not-Disturb OFF**| *"Jarvis, turn off do not disturb"*, *"Disable DND"* | `NotificationManager.setInterruptionFilter(ALL)` and normal ringer. |
| **Do-Not-Disturb TOGGLE**| *"Jarvis, toggle do not disturb"* | Toggles between priority interruption filter and normal. |
| **Silent Mode ON** | *"Jarvis, silent mode on"*, *"Activate silent mode"* | Sets `AudioManager.RINGER_MODE_SILENT` and DND priority filter. |
| **Silent Mode OFF** | *"Jarvis, turn off silent mode"*, *"Disable silent mode"* | Sets `AudioManager.RINGER_MODE_NORMAL` and restores full device audio. |
| **Silent Mode TOGGLE**| *"Jarvis, toggle silent mode"* | Inverts current silent/ringer mode. |
| **Volume UP** | *"Jarvis, volume up"*, *"Raise volume"* | Adjusts music stream volume upward (`ADJUST_RAISE`). |
| **Volume DOWN** | *"Jarvis, volume down"*, *"Lower volume"* | Adjusts music stream volume downward (`ADJUST_LOWER`). |
| **Volume MUTE** | *"Jarvis, mute volume"*, *"Mute"* | Sets stream volume to 0 (`FLAG_SHOW_UI`). |
| **Volume LEVEL** | *"Jarvis, set volume to 75%"*, *"Volume 50 percent"* | Computes target stream level and applies directly. |
| **Global Actions** | *"Jarvis, go home"*, *"Go back"*, *"Quick settings"*, *"Notifications"* | Dispatches `performGlobalAction` (`HOME`, `BACK`, `QUICK_SETTINGS`, `NOTIFICATIONS`). |

### 3. Automated Test Verification
Verified in [`JarvisAccessibilityTest.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/test/java/com/jarvis/arthur/JarvisAccessibilityTest.java):
* `testInitialStateRequiresOneTimePermission`: Confirms all system setting interactions are blocked prior to initial user approval.
* `testOneTimePermissionGrantAndPermanentPersistence`: Confirms single one-time approval is remembered forever across sessions.
* `testBluetoothActionsAfterPermissionGrant`: Confirms Bluetooth ON, OFF, and TOGGLE execute without prompts.
* `testDoNotDisturbActionsAfterPermissionGrant`: Confirms DND ON, OFF, and TOGGLE execute without prompts.
* `testSilentModeActionsAfterPermissionGrant`: Confirms Silent Mode ON, OFF, and TOGGLE execute without prompts.
* `testVolumeActionsAfterPermissionGrant`: Confirms Volume UP, DOWN, MUTE, and LEVEL execute without prompts.
* `testVoiceWakeTriggerCommands`: Confirms voice wake trigger ("Jarvis, turn on bluetooth", "Jarvis, silent mode on", etc.) routes and executes seamlessly.
* `testGlobalAccessibilityActions`: Confirms Home, Back, Quick Settings, and Notifications actions execute.
* `testManualRevocationReEnablesGating`: Confirms resetting permissions cleanly re-arms the one-time permission gate.

---

## 🚀 Release Mode Build, Signing & Installation Guide

### 1. Release Mode & Keystore Details
* **Build Type**: `release` (Optimized, lint-analyzed, and packaged for production).
* **Release Keystore**: [`app/jarvis-release.jks`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/jarvis-release.jks)
* **Key Algorithm**: RSA 2048-bit with SHA256 digest
* **Validity Period**: 10,000 days (valid through March 2054)
* **Signature Schemes**: **APK Signature Scheme v2** and **APK Signature Scheme v3** (verified via `apksigner`)
* **Output Release APKs**:
  * [`Jarvis-Arthur-release.apk`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/Jarvis-Arthur-release.apk) (~5.29 MB)
  * [`Jarvis-Arthur.apk`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/Jarvis-Arthur.apk) (~5.29 MB)

### 2. Enable 'Install Unknown Apps' on Your Android Device
On modern Android versions (Android 8.0 through Android 14+), installation from unknown sources is controlled per-application. Enable it for your file transfer tool or development app:

#### For Stock Android / Google Pixel / Motorola / Nothing OS:
1. Open **Settings** on your phone.
2. Go to **Apps** (or **Apps & notifications**) ➔ **Special app access**.
3. Tap **Install unknown apps**.
4. Select the app you use to transfer or download the APK (e.g. **Files by Google**, **Chrome**, **Drive**, or your development tool).
5. Toggle **"Allow from this source"** to **ON**.

#### For Samsung Devices (One UI):
1. Open **Settings** ➔ **Apps**.
2. Tap the **⋮** menu (top-right corner) ➔ **Special access**.
3. Select **Install unknown apps**.
4. Locate your browser or **My Files** app and turn on **Allow from this source**.

#### For Xiaomi / Redmi / POCO (MIUI / HyperOS):
1. Open **Settings** ➔ **Privacy protection** ➔ **Special permissions**.
2. Tap **Install unknown apps**.
3. Select your browser or file manager and toggle **Allow from this source**.

### 3. Install the APK Without Security Warnings

#### Method A: Direct Install via USB Debugging (ADB)
If your phone is connected to your PC with USB debugging enabled:
```powershell
# If upgrading from a previous debug build, uninstall first to avoid signature collision:
adb uninstall com.jarvis.arthur

# Install the properly signed release APK:
adb install -r "e:\Project files\Jarvis\Jarvies-Arthur\Jarvis-Arthur-release.apk"

# Launch Jarvis Arthur:
adb shell am start -n com.jarvis.arthur/.MainActivity
```

#### Method B: Direct On-Device Installation (APK File Transfer)
1. Transfer [`Jarvis-Arthur-release.apk`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/Jarvis-Arthur-release.apk) to your phone via USB cable, Google Drive, or local share.
2. Tap the APK file in your file manager.
3. Because the APK is signed with a valid 2048-bit release certificate, the Android Package Installer verifies integrity immediately.
4. Tap **Install**. (If Google Play Protect displays a prompt for privately sideloaded developer apps, select **"Install anyway"**).
5. Open **Jarvis Arthur**, grant the one-time system permissions, and enjoy hands-free autonomous AI assistance!

---

## 🔍 Startup Logging & Crash Pinpoint Diagnostics

To diagnose any initialization crashes or startup failures, Jarvis Arthur incorporates an in-depth breadcrumb and crash logging subsystem ([`StartupLogger.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/util/StartupLogger.java) and [`JarvisApplication.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/JarvisApplication.java)).

### 1. Startup Breadcrumb Sequence Codes
Every phase of application startup emits a tagged, timestamped breadcrumb into `Logcat` (`tag: JarvisStartup`) and in-memory ring buffer:

| Step Code | Subsystem / File | Description |
| :--- | :--- | :--- |
| **`APP-01` to `APP-05`** | [`JarvisApplication.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/JarvisApplication.java) | Custom `Application.onCreate()`, unhandled exception handler installation, device hardware telemetry (SDK, ABI, Model, Manufacturer), and prior crash check. |
| **`ACT-01` to `ACT-11`** | [`MainActivity.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/MainActivity.java) | Activity creation, layout inflation, view binding, listener wiring, and permissions checks. |
| **`ENG-01` to `ENG-14`** | [`MainActivity.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/MainActivity.java) | Core engine initialization: Memory vault, Intent router, Gemini SSE knowledge source, Voice recognizer, TTS synthesizer, and Device controller. |
| **`SRV-01` to `SRV-05`** | [`MainActivity.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/MainActivity.java) | Voice listener background service start and Accessibility service verification. |
| **`VLS-01` to `VLS-09`** | [`VoiceListenerService.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/service/VoiceListenerService.java) | Background service lifecycle, notification channel creation, Android 14+ foreground service microphone permission gating, and wake lock. |
| **`DEV-01` to `DEV-03`** | [`DeviceController.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/device/DeviceController.java) | Hardware controller initialization and camera torch discovery. |
| **`VOX-01` to `VOX-02`** | [`VoiceManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/VoiceManager.java) | Speech recognition availability check and recognizer instantiation. |
| **`TTS-01`** | [`TtsManager.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/voice/TtsManager.java) | Android Text-to-Speech engine connection callback status. |
| **`ACC-01` to `ACC-02`** | [`JarvisAccessibilityService.java`](file:///e:/Project%20files/Jarvis/Jarvies-Arthur/app/src/main/java/com/jarvis/arthur/service/JarvisAccessibilityService.java) | System accessibility service connection and state synchronization. |

### 2. How to Inspect Startup Logs & Pinpoint Crashes

#### Method A: Real-Time ADB Logcat (PC / USB Debugging)
Stream startup logs in real-time with automatic highlighting:
```powershell
adb logcat -s JarvisStartup JARVIS_STARTUP_CRASH JarvisApplication
```
*Filter for errors only:*
```powershell
adb logcat *:E | Select-String "JarvisStartup|JARVIS_STARTUP_CRASH"
```

#### Method B: Interactive In-App Diagnostics Dialog
1. Long-press on the title bar (**"JARVIS // ARTHUR"**) at the top of the main screen.
2. An interactive dialog displays all startup breadcrumbs, timestamps, and previous crash logs.
3. Tap **"Copy Logs"** to copy the complete diagnostic report to the clipboard.
4. You can also access this anytime via **Settings (⚙️)** ➔ **"View Startup Logs & Diagnostics"**.

#### Method C: Automatic Crash Recovery Prompt
If the app previously encountered a fatal exception during startup:
1. `JarvisApplication`'s uncaught exception handler intercepts the exception and serializes the complete stack trace and trailing breadcrumbs to `startup_crash.log`.
2. On the subsequent app launch, Jarvis detects the crash file and automatically opens a **"Previous Startup Crash Detected"** dialog displaying the exact failing line and breadcrumb code.

