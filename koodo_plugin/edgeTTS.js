// ── Edge TTS Plugin for Koodo Reader ──────────────────────────────────
// Optimised for low memory & high performance:
//   • Streaming HTTP response — no full-body buffering
//   • Aggressive cleanup: keeps only 2 recent files
//   • Reuses require() calls (module cache)
//   • Minimal silent MP3 for empty-text / error fallback

const SILENT_MP3 = Buffer.from(
  "//uQxAAAAAANIAAAAAExBTUUzLjEwMFVVVVVVVVVVVVVVVVVV" +
  "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
  "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV" +
  "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV",
  "base64"
);

// Cache module references once — avoids repeated require() overhead
const path = require("path");
const fs = require("fs");
const axios = require("axios");

// ── Monkey-patch Koodo Reader's Howl constructor in Renderer Process ──
try {
  const { app, BrowserWindow } = require("electron");

  const injectPatch = (win) => {
    if (!win || win.isDestroyed()) return;
    const patchScript = `
      (function() {
        if (window.Howl && !window.Howl.isPatched) {
          const OriginalHowl = window.Howl;
          window.Howl = function(options) {
            if (options && options.src && options.src[0] === "skip-empty-page") {
              const mockHowl = {
                _onload: options.onload,
                _onend: null,
                play: function() {
                  setTimeout(() => {
                    if (this._onend) this._onend();
                  }, 10);
                },
                on: function(event, cb) {
                  if (event === 'end') {
                    this._onend = cb;
                  }
                },
                stop: function() {},
                pause: function() {},
                unload: function() {}
              };
              setTimeout(() => {
                if (options.onload) options.onload();
              }, 5);
              return mockHowl;
            }

            // Track and unload previous instances to prevent memory leak
            if (window.lastHowlInstance) {
              try { window.lastHowlInstance.unload(); } catch(e) {}
            }
            const inst = new OriginalHowl(options);
            window.lastHowlInstance = inst;
            return inst;
          };
          window.Howl.isPatched = true;
          console.log("Koodo Reader Howl player memory leak & skip patch applied successfully!");
        }
      })();
    `;
    win.webContents.executeJavaScript(patchScript).catch(() => {});
  };

  // Register for future windows
  app.on("browser-window-created", (event, window) => {
    window.webContents.on("did-navigate", () => injectPatch(window));
    window.webContents.on("dom-ready", () => injectPatch(window));
  });

  // Inject into any currently open windows
  BrowserWindow.getAllWindows().forEach(injectPatch);
} catch (e) {
  console.log("Failed to register Koodo Reader memory leak & skip patch:", e.message || e);
}

const isSkipPage = (text) => {
  if (!text) return true;

  // 1. Remove HTML tags completely
  let cleanText = text.replace(/<\/?[^>]+(>|$)/g, "");

  // 2. Remove HTML entities: &nbsp; &amp; etc.
  cleanText = cleanText.replace(/&[a-zA-Z0-9#]+;/g, "");

  // 3. Remove markdown images: ![alt](url)
  cleanText = cleanText.replace(/!\[.*?\]\(.*?\)/g, "");

  // 4. Remove all characters except Unicode letters and numbers
  cleanText = cleanText.replace(/[^\p{L}\p{N}]/gu, "");

  // 5. If nothing is left, it's an empty page or a picture-only page
  return cleanText.length === 0;
};

const getAudioPath = async (text, speed, dirPath, config) => {
  const ttsDir = path.join(dirPath, "tts");

  // Ensure directory exists (sync is fine — single call, cached by OS)
  if (!fs.existsSync(ttsDir)) {
    fs.mkdirSync(ttsDir, { recursive: true });
  }

  // ── Cleanup old files ──────────────────────────────────────────
  // Keep only the 2 most recent to avoid disk bloat.
  // Use a try/catch so cleanup failures never block playback.
  try {
    const files = fs.readdirSync(ttsDir);
    if (files.length > 2) {
      const sorted = files
        .filter((f) => f.endsWith(".mp3"))
        .map((f) => ({
          name: f,
          full: path.join(ttsDir, f),
          time: parseInt(f, 10) || 0,
        }))
        .sort((a, b) => b.time - a.time);
      for (let i = 2; i < sorted.length; i++) {
        try { fs.unlinkSync(sorted[i].full); } catch (_) {}
      }
    }
  } catch (_) {}

  // ── Empty / Picture page guard ──────────────────────────────────
  if (isSkipPage(text)) {
    return "skip-empty-page";
  }

  const audioPath = path.join(ttsDir, Date.now() + ".mp3");

  // ── Fetch audio via streaming ──────────────────────────────────
  const baseUrl = config.baseUrl || "http://127.0.0.1:8000";
  const voiceName = config.voiceName || "en-US-AriaNeural";
  const speedVal = speed ? Math.min(2.0, Math.max(0.5, speed)) : 1.0;

  try {
    const response = await axios.post(
      baseUrl + "/v1/audio/speech",
      { text, voice: voiceName, speed: speedVal },
      {
        headers: { "Content-Type": "application/json" },
        responseType: "stream",
        timeout: 30000,
      }
    );

    // Stream response directly to file — never hold full buffer in RAM
    await new Promise((resolve, reject) => {
      const writer = fs.createWriteStream(audioPath);
      response.data.pipe(writer);
      writer.on("finish", resolve);
      writer.on("error", reject);
      // Safety: if the stream stalls, don't hang forever
      response.data.on("error", (err) => {
        writer.close();
        reject(err);
      });
    });

    return audioPath;
  } catch (e) {
    console.log("TTS request failed:", e.message || e);
    // Write silent MP3 on error — prevents Koodo retry loop
    fs.writeFileSync(audioPath, SILENT_MP3);
    return audioPath;
  }
};

const getTTSVoice = async (config) => {
  const voices = [
    { name: "en-US-AriaNeural", gender: "female", label: "Aria (US)" },
    { name: "en-US-JennyNeural", gender: "female", label: "Jenny (US)" },
    { name: "en-US-MichelleNeural", gender: "female", label: "Michelle (US)" },
    { name: "en-US-AnaNeural", gender: "female", label: "Ana (US)" },
    { name: "en-US-GuyNeural", gender: "male", label: "Guy (US)" },
    { name: "en-US-ChristopherNeural", gender: "male", label: "Christopher (US)" },
    { name: "en-US-EricNeural", gender: "male", label: "Eric (US)" },
    { name: "en-US-RogerNeural", gender: "male", label: "Roger (US)" },
    { name: "en-US-SteffanNeural", gender: "male", label: "Steffan (US)" },
    { name: "en-GB-SoniaNeural", gender: "female", label: "Sonia (British)" },
    { name: "en-GB-LibbyNeural", gender: "female", label: "Libby (British)" },
    { name: "en-GB-MaisieNeural", gender: "female", label: "Maisie (British)" },
    { name: "en-GB-RyanNeural", gender: "male", label: "Ryan (British)" },
    { name: "en-GB-ThomasNeural", gender: "male", label: "Thomas (British)" },
    { name: "en-AU-NatashaNeural", gender: "female", label: "Natasha (Australian)" },
    { name: "en-AU-WilliamNeural", gender: "male", label: "William (Australian)" },
    { name: "de-DE-KatjaNeural", gender: "female", label: "Katja (Deutsch)" },
    { name: "de-DE-ConradNeural", gender: "male", label: "Conrad (Deutsch)" },
  ];
  return voices.map((v) => ({
    name: v.name,
    gender: v.gender,
    locale: v.name.split("-").slice(0, 2).join("-"),
    displayName: "Edge TTS - " + v.label,
    plugin: "edge-tts-local",
    config: { ...config, voiceName: v.name },
  }));
};

global.getAudioPath = getAudioPath;
global.getTTSVoice = getTTSVoice;
