# Edge TTS — Premium Neural Voices for Koodo Reader & Android

Free, high-quality text-to-speech using Microsoft Edge's neural voice engine. Two components:

1. **Koodo Reader Plugin** — TTS plugin for Koodo Reader (desktop & web)
2. **Android TTS Engine** — Standalone APK that registers as a system-wide TTS engine on Android

---

## 🖥️ Koodo Reader Plugin (Desktop)

### Quick Start
1. Double-click `setup.bat` — installs dependencies and starts the server
2. In Koodo Reader → Settings → Plugins → Import `koodo_plugin/edgeTTS.json`
3. Select an Edge TTS voice and start reading!

### How It Works
- `server.py` — Lightweight API server that streams audio from Edge TTS
- `koodo_plugin/edgeTTS.js` — Plugin that sends text to the server and pipes audio to file
- Server streams audio chunks directly — **zero RAM buffering**

### Using from Android (Koodo Reader on phone)
1. Run `setup.bat` on your PC (keep it running)
2. The server shows your PC's local IP (e.g., `http://192.168.1.x:8000`)
3. In the plugin config, set `baseUrl` to that IP address

---

## 📱 Android TTS Engine (Standalone APK)

The Android app works **completely independently** — no PC or server needed.
It connects directly to Microsoft's speech synthesis servers from your phone.

### Building the APK

**Prerequisites:** JDK 17+ (the build script will try to install it if missing)

```
build_apk.bat
```

This will:
1. Check for JDK (installs via `winget` if missing)
2. Accept Android SDK licenses
3. Download the Android SDK automatically
4. Build the APK → `EdgeTTS.apk`

### Installing on Android
1. Transfer `EdgeTTS.apk` to your phone
2. Install it (enable "Install from unknown sources" if prompted)
3. Open the app → select a voice → tap **Test Voice**
4. Go to **Settings → Accessibility → Text-to-speech output**
5. Select **Edge TTS** as your preferred engine
6. Any app using Android TTS will now use Edge voices!

### Supported Languages
English (US, British, Australian), German, French, Spanish, Italian,
Portuguese, Japanese, Korean, Chinese — with multiple voices per language.

---

## ⚡ Performance Optimisations

### Server (v2)
- **Streaming response** — audio chunks stream to the client as they arrive from Edge TTS, instead of buffering the entire file in memory
- Memory stays flat regardless of text length

### Plugin (v2)
- **Stream-to-file** — server response is piped directly to disk via Node.js streams, never buffered in RAM
- **Module caching** — `require()` calls moved to top level
- **Aggressive cleanup** — keeps only 2 recent audio files, deletes older ones per request
- **Silent MP3 fallback** — empty text and errors return a minimal silent frame, preventing Koodo from freezing

### Android App
- **Pre-sized buffers** — audio buffer pre-allocated based on text length estimate
- **Shared OkHttpClient** — connection pooling reduces GC pressure
- **Temp file cleanup** — MP3→PCM decoder cleans up immediately after use
- **Binary frame parsing** — extracts audio data in-place without intermediate copies

---

## 📁 Project Structure

```
tts/
├── setup.bat              # One-click server setup & start
├── server.py              # Optimised Edge TTS API server
├── build_plugin.py        # Rebuilds the Koodo plugin JSON
├── build_apk.bat          # One-click Android APK builder
├── bootstrap_android.py   # Downloads Gradle wrapper JAR
├── koodo_plugin/
│   ├── edgeTTS.js         # Koodo Reader TTS plugin
│   └── edgeTTS.json       # Built plugin (import this into Koodo)
└── android/               # Android TTS engine project
    ├── gradlew.bat         # Gradle wrapper (builds without IDE)
    ├── app/src/main/java/com/edgetts/engine/
    │   ├── EdgeTtsClient.java   # WebSocket client (direct to MS servers)
    │   ├── EdgeTtsService.java  # Android TTS engine service
    │   ├── Mp3Decoder.java      # MP3→PCM decoder (MediaCodec)
    │   └── SettingsActivity.java # Voice selection & test UI
    └── app/src/main/res/        # Layouts, drawables, icons
```
