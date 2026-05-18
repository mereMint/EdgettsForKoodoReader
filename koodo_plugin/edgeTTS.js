const getAudioPath = async (text, speed, dirPath, config) => {
  const path = require("path");
  const fs = require("fs");
  const ttsDir = path.join(dirPath, "tts");

  // Ensure tts directory exists
  if (!fs.existsSync(ttsDir)) {
    fs.mkdirSync(ttsDir);
    console.log("folder created successfully");
  }

  // --- MEMORY LEAK FIX: clean up old audio files ---
  // Keep only the most recent few files to avoid unbounded disk growth.
  // Koodo creates a new file per sentence/paragraph, so over a long reading
  // session thousands of files accumulate (10GB+).
  try {
    const files = fs.readdirSync(ttsDir)
      .filter((f) => f.endsWith(".mp3"))
      .map((f) => ({
        name: f,
        full: path.join(ttsDir, f),
        time: parseInt(f.replace(".mp3", ""), 10) || 0,
      }))
      .sort((a, b) => b.time - a.time); // newest first

    // Keep the 3 most recent files (safety buffer for playback overlap),
    // delete everything older.
    const toDelete = files.slice(3);
    for (const f of toDelete) {
      try { fs.unlinkSync(f.full); } catch (_) { /* ignore */ }
    }
  } catch (_) { /* ignore cleanup errors */ }

  // Generate new audio
  let audioName = new Date().getTime() + ".mp3";
  let audioData = await getTTSAudio(text, speed, config);
  fs.writeFileSync(path.join(ttsDir, audioName), audioData);

  // Release the buffer reference immediately so GC can reclaim it
  audioData = null;

  return path.join(ttsDir, audioName);
};
const getTTSAudio = async (text, speed, config) => {
  let baseUrl = config.baseUrl || "http://127.0.0.1:8000";
  let voiceName = config.voiceName || "en-US-AriaNeural";
  let speedVal = speed ? Math.min(2.0, Math.max(0.5, speed)) : 1.0;
  const axios = require("axios");
  return new Promise((resolve, reject) => {
    axios
      .post(
        baseUrl + "/v1/audio/speech",
        { text: text, voice: voiceName, speed: speedVal },
        { headers: { "Content-Type": "application/json" }, responseType: "arraybuffer" }
      )
      .then((r) => {
        // Extract only the data buffer; drop the full axios response
        // (which holds headers, config, request objects) so it can be GC'd
        const data = r.data;
        r.data = null;
        resolve(data);
      })
      .catch((e) => { console.log(e); reject(""); });
  });
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
  return Promise.resolve(
    voices.map((v) => ({
      name: v.name,
      gender: v.gender,
      locale: v.name.split("-").slice(0, 2).join("-"),
      displayName: "Edge TTS - " + v.label,
      plugin: "edge-tts-local",
      config: { ...config, voiceName: v.name },
    }))
  );
};
global.getAudioPath = getAudioPath;
global.getTTSVoice = getTTSVoice;
