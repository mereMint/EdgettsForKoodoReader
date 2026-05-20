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
const http = require("http");
const url = require("url");

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

  const audioPath = path.join(ttsDir, Date.now() + ".mp3");

  // ── Empty text guard ───────────────────────────────────────────
  if (!text || !text.trim()) {
    fs.writeFileSync(audioPath, SILENT_MP3);
    return audioPath;
  }

  // ── Fetch audio via streaming ──────────────────────────────────
  const baseUrl = config.baseUrl || "http://127.0.0.1:8000";
  const voiceName = config.voiceName || "en-US-AriaNeural";
  const speedVal = speed ? Math.min(2.0, Math.max(0.5, speed)) : 1.0;

  try {
    const body = JSON.stringify({ text, voice: voiceName, speed: speedVal });
    const parsed = new url.URL(baseUrl + "/v1/audio/speech");

    await new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: parsed.hostname,
          port: parsed.port || 80,
          path: parsed.pathname,
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(body),
          },
          timeout: 30000,
        },
        (res) => {
          if (res.statusCode !== 200) {
            reject(new Error("HTTP " + res.statusCode));
            return;
          }
          const writer = fs.createWriteStream(audioPath);
          res.pipe(writer);
          writer.on("finish", resolve);
          writer.on("error", reject);
          res.on("error", (err) => {
            writer.close();
            reject(err);
          });
        }
      );
      req.on("error", reject);
      req.on("timeout", () => {
        req.destroy();
        reject(new Error("Request timed out"));
      });
      req.write(body);
      req.end();
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
