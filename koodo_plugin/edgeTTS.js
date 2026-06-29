/* Edge TTS voice plugin for Koodo Reader on Linux.
 * Keep this script small and side-effect free: Koodo evals it in the Electron
 * main process for every TTS request. It must only define global.getAudioPath.
 */

const SILENT_WAV = Buffer.from(
  "UklGRiQAAABXQVZFZm10IBAAAAABAAEAgF4AAAB9AAACABAAZGF0YQAAAAA=",
  "base64"
);

const isBlankPage = (text) => {
  if (!text) return true;
  let cleanText = String(text).replace(/<\/?[^>]+(>|$)/g, "");
  cleanText = cleanText.replace(/&[a-zA-Z0-9#]+;/g, "");
  cleanText = cleanText.replace(/!\[.*?\]\(.*?\)/g, "");
  cleanText = cleanText.replace(/[^\p{L}\p{N}]/gu, "");
  return cleanText.length === 0;
};

const ensureDir = (fs, dir) => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
};

const cleanupOldAudio = (fs, path, ttsDir) => {
  try {
    const now = Date.now();
    const maxAgeMs = 10 * 60 * 1000;
    for (const file of fs.readdirSync(ttsDir)) {
      if (file === "silent.wav") continue;
      if (!file.endsWith(".wav") && !file.endsWith(".mp3")) continue;
      const fullPath = path.join(ttsDir, file);
      const stat = fs.statSync(fullPath);
      if (now - stat.mtimeMs > maxAgeMs) {
        try { fs.unlinkSync(fullPath); } catch (_) {}
      }
    }
  } catch (_) {}
};

const getSilentAudioPath = (fs, path, ttsDir) => {
  const silentPath = path.join(ttsDir, "silent.wav");
  try {
    if (!fs.existsSync(silentPath) || fs.statSync(silentPath).size < 44) {
      fs.writeFileSync(silentPath, SILENT_WAV);
    }
  } catch (_) {
    fs.writeFileSync(silentPath, SILENT_WAV);
  }
  return silentPath;
};

const getAudioPath = async (text, speed, dirPath, config) => {
  const path = require("path");
  const fs = require("fs");

  const ttsDir = path.join(dirPath, "tts");
  ensureDir(fs, ttsDir);
  cleanupOldAudio(fs, path, ttsDir);

  if (isBlankPage(text)) {
    return getSilentAudioPath(fs, path, ttsDir);
  }

  const axios = require("axios");
  const { pipeline } = require("stream/promises");

  const uniqueId = Date.now() + "_" + Math.random().toString(36).slice(2, 8);
  const audioPath = path.join(ttsDir, uniqueId + ".wav");
  const tmpPath = audioPath + ".part";

  const baseUrl = (config && config.baseUrl) || "http://127.0.0.1:8000";
  const voiceName = (config && config.voiceName) || "en-US-AriaNeural";
  const speedVal = speed ? Math.min(2.0, Math.max(0.5, speed)) : 1.0;

  try {
    const response = await axios.post(
      baseUrl + "/v1/audio/speech",
      { text, voice: voiceName, speed: speedVal, format: "wav" },
      {
        headers: { "Content-Type": "application/json" },
        responseType: "stream",
        timeout: 120000,
      }
    );

    await pipeline(response.data, fs.createWriteStream(tmpPath));
    const stat = fs.statSync(tmpPath);
    if (stat.size < 44) {
      try { fs.unlinkSync(tmpPath); } catch (_) {}
      return getSilentAudioPath(fs, path, ttsDir);
    }

    fs.renameSync(tmpPath, audioPath);
    return audioPath;
  } catch (e) {
    console.log("Edge TTS request failed:", e.message || e);
    try { fs.unlinkSync(tmpPath); } catch (_) {}
    return getSilentAudioPath(fs, path, ttsDir);
  }
};

global.getAudioPath = getAudioPath;
