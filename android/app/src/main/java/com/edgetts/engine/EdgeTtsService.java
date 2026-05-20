package com.edgetts.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.os.Build;
import android.os.PowerManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Android TextToSpeechService implementation backed by Edge TTS.
 * 
 * This service registers with the Android system as a TTS engine.
 * Once installed, users can select "Edge TTS" in:
 *   Settings → Accessibility → Text-to-speech output → Preferred engine
 *
 * The service works completely standalone — it connects directly to
 * Microsoft's speech synthesis WebSocket servers from the device.
 * No PC, no server, no additional setup required.
 *
 * Reliability features:
 *   - Runs as a foreground service to prevent Android from killing it
 *   - Holds a wake lock during synthesis to prevent CPU sleep
 *   - Retries failed synthesis attempts with exponential backoff
 *   - Survives reader app being closed or backgrounded
 *
 * Threading model: onSynthesizeText runs on a dedicated synthesis thread
 * provided by the Android framework. Network calls are safe here.
 */
public class EdgeTtsService extends TextToSpeechService {

    private static final String TAG = "EdgeTtsService";
    private static final String CHANNEL_ID = "edge_tts_synthesis";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {500, 1500, 3000};

    // Voice registry: locale → voice names available for that locale
    // Package-visible so CheckVoiceDataActivity can enumerate voices
    static final Map<String, String[]> VOICES = new HashMap<>();
    // Voice name → gender mapping
    static final Map<String, String> VOICE_GENDERS = new HashMap<>();
    // Set of supported locale strings for fast lookup
    static final Set<String> SUPPORTED_LOCALES = new HashSet<>();

    static {
        // English (US)
        VOICES.put("en-US", new String[]{
                "en-US-AriaNeural", "en-US-JennyNeural", "en-US-MichelleNeural",
                "en-US-AnaNeural", "en-US-GuyNeural", "en-US-ChristopherNeural",
                "en-US-EricNeural", "en-US-RogerNeural", "en-US-SteffanNeural"
        });
        // English (GB)
        VOICES.put("en-GB", new String[]{
                "en-GB-SoniaNeural", "en-GB-LibbyNeural", "en-GB-MaisieNeural",
                "en-GB-RyanNeural", "en-GB-ThomasNeural"
        });
        // English (AU)
        VOICES.put("en-AU", new String[]{
                "en-AU-NatashaNeural", "en-AU-WilliamNeural"
        });
        // German
        VOICES.put("de-DE", new String[]{
                "de-DE-KatjaNeural", "de-DE-ConradNeural", "de-DE-AmalaNeural",
                "de-DE-BerndNeural", "de-DE-ChristophNeural", "de-DE-ElkeNeural",
                "de-DE-GiselaNeural", "de-DE-KasperNeural", "de-DE-KillianNeural",
                "de-DE-KlarissaNeural", "de-DE-KlausNeural", "de-DE-LouisaNeural",
                "de-DE-MajaNeural", "de-DE-RalfNeural", "de-DE-TanjaNeural"
        });
        // French
        VOICES.put("fr-FR", new String[]{
                "fr-FR-DeniseNeural", "fr-FR-HenriNeural", "fr-FR-EloiseNeural"
        });
        // Spanish
        VOICES.put("es-ES", new String[]{
                "es-ES-ElviraNeural", "es-ES-AlvaroNeural"
        });
        // Italian
        VOICES.put("it-IT", new String[]{
                "it-IT-ElsaNeural", "it-IT-IsabellaNeural", "it-IT-DiegoNeural"
        });
        // Portuguese
        VOICES.put("pt-BR", new String[]{
                "pt-BR-FranciscaNeural", "pt-BR-AntonioNeural"
        });
        // Japanese
        VOICES.put("ja-JP", new String[]{
                "ja-JP-NanamiNeural", "ja-JP-KeitaNeural"
        });
        // Korean
        VOICES.put("ko-KR", new String[]{
                "ko-KR-SunHiNeural", "ko-KR-InJoonNeural"
        });
        // Chinese
        VOICES.put("zh-CN", new String[]{
                "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural"
        });

        // Build gender map and supported locales set
        for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
            SUPPORTED_LOCALES.add(entry.getKey());
            for (String v : entry.getValue()) {
                // Heuristic: common female name patterns
                String lower = v.toLowerCase();
                boolean isFemale = lower.contains("aria") || lower.contains("jenny") ||
                        lower.contains("michelle") || lower.contains("ana") ||
                        lower.contains("sonia") || lower.contains("libby") ||
                        lower.contains("maisie") || lower.contains("natasha") ||
                        lower.contains("katja") || lower.contains("amala") ||
                        lower.contains("elke") || lower.contains("gisela") ||
                        lower.contains("klarissa") || lower.contains("louisa") ||
                        lower.contains("maja") || lower.contains("tanja") ||
                        lower.contains("denise") || lower.contains("eloise") ||
                        lower.contains("elvira") || lower.contains("elsa") ||
                        lower.contains("isabella") || lower.contains("francisca") ||
                        lower.contains("nanami") || lower.contains("sunhi") ||
                        lower.contains("xiaoxiao");
                VOICE_GENDERS.put(v, isFemale ? "female" : "male");
            }
        }
    }

    // Current selected voice for synthesis
    private volatile String currentVoice = "en-US-AriaNeural";
    private volatile boolean stopRequested = false;

    // Foreground service & wake lock management
    private PowerManager.WakeLock wakeLock;
    private volatile boolean isForeground = false;
    private final Object foregroundLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        // Load saved voice preference
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentVoice = prefs.getString("selected_voice", "en-US-AriaNeural");

        // Acquire a partial wake lock to prevent CPU sleep during synthesis
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EdgeTTS::SynthesisLock");
        wakeLock.setReferenceCounted(false);

        Log.i(TAG, "Edge TTS Service created. Voice: " + currentVoice);
    }

    @Override
    public void onDestroy() {
        // Release wake lock if held
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }





    // ─── Language Support ────────────────────────────────────────────

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        
        // Try direct ISO-2 match first
        String c = (country == null) ? "" : country;
        String localeStr = lang + (c.isEmpty() ? "" : "-" + c);
        if (SUPPORTED_LOCALES.contains(localeStr)) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        for (String key : SUPPORTED_LOCALES) {
            if (key.startsWith(lang + "-")) {
                return TextToSpeech.LANG_AVAILABLE;
            }
        }
        
        // Try ISO-3 match (Android framework usually passes ISO-3 codes like 'eng', 'deu')
        for (String supported : SUPPORTED_LOCALES) {
            String[] parts = supported.split("-");
            Locale voiceLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
            try {
                if (voiceLocale.getISO3Language().equals(lang)) {
                    if (c.isEmpty() || voiceLocale.getISO3Country().equals(c)) {
                        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
                    }
                    return TextToSpeech.LANG_AVAILABLE;
                }
            } catch (Exception ignored) {}
        }

        return TextToSpeech.LANG_NOT_SUPPORTED; // IMPORTANT: Must not blindly return LANG_AVAILABLE for unknown languages!
    }

    @Override
    protected String[] onGetLanguage() {
        String voice = (currentVoice != null) ? currentVoice : "en-US-AriaNeural";
        String[] parts = voice.split("-");
        Locale locale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
        try {
            return new String[]{
                    locale.getISO3Language(),
                    locale.getISO3Country(),
                    ""
            };
        } catch (Exception e) {
            return new String[]{"eng", "USA", ""};
        }
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    // ─── Voice API Support ───────────────────────────────────────────

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
            String localeStr = entry.getKey(); // e.g. "en-US"
            String[] parts = localeStr.split("-");
            Locale locale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
            
            for (String voiceName : entry.getValue()) {
                // Determine features based on our mappings
                Set<String> features = new HashSet<>();
                // Do NOT set network connection required to true, otherwise Koodo Reader
                // will filter these out of the 'Local voices' tab!
                Voice voice = new Voice(
                        voiceName,
                        locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false, // requires network (set to false so Koodo sees it as a local voice)
                        features
                );
                voices.add(voice);
            }
        }
        return voices;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        if (onIsValidVoiceName(voiceName) == TextToSpeech.SUCCESS) {
            return TextToSpeech.SUCCESS;
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        if (voiceName != null && VOICE_GENDERS.containsKey(voiceName)) {
            return TextToSpeech.SUCCESS;
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        if (lang == null) return currentVoice;
        
        // Try direct ISO-2 match
        String c = (country == null) ? "" : country;
        String localeStr = lang + (c.isEmpty() ? "" : "-" + c);
        if (VOICES.containsKey(localeStr)) {
            return VOICES.get(localeStr)[0];
        }
        for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
            if (entry.getKey().startsWith(lang + "-")) {
                return entry.getValue()[0];
            }
        }
        
        // Try ISO-3 match (Android framework passes 'eng', 'deu' etc)
        for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
            String[] parts = entry.getKey().split("-");
            Locale voiceLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
            try {
                if (voiceLocale.getISO3Language().equals(lang)) {
                    if (c.isEmpty() || voiceLocale.getISO3Country().equals(c)) {
                        return entry.getValue()[0]; // Return matching voice
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Try ISO-3 language-only match
        for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
            String[] parts = entry.getKey().split("-");
            Locale voiceLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
            try {
                if (voiceLocale.getISO3Language().equals(lang)) {
                    return entry.getValue()[0];
                }
            } catch (Exception ignored) {}
        }
        
        return currentVoice; // Fallback to current configured voice
    }

    // ─── Synthesis ───────────────────────────────────────────────────

    @Override
    protected void onStop() {
        stopRequested = true;
        Log.d(TAG, "Synthesis stop requested");
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;

        // Re-read voice preference in case the user changed it in SettingsActivity
        // while the service was already running
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentVoice = prefs.getString("selected_voice", currentVoice);

        String text = request.getCharSequenceText() != null
                ? request.getCharSequenceText().toString()
                : "";

        if (text.trim().isEmpty()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }

        // Acquire wake lock before doing any work
        acquireWakeLock();

        try {
            synthesizeWithRetry(request, callback, text);
        } catch (Exception e) {
            // Catch-all: ensure the callback always gets a response so Android
            // doesn't hang waiting for audio that will never come.
            Log.e(TAG, "Unexpected error in onSynthesizeText", e);
            try {
                callback.error();
            } catch (Exception ignored) {}
        } finally {
            releaseWakeLock();
        }
    }

    /**
     * Perform synthesis with automatic retry on transient failures.
     * This handles network hiccups, temporary server errors, and
     * clock synchronization failures gracefully.
     */
    private void synthesizeWithRetry(SynthesisRequest request, SynthesisCallback callback, String text) {
        // Determine voice to use
        String voice = resolveVoice(request);

        // Calculate rate string from speech rate parameter
        // Android passes rate as percentage (100 = normal)
        int speechRate = request.getSpeechRate();
        int ratePercent = speechRate > 0 ? (speechRate - 100) : 0;
        ratePercent = Math.max(-50, Math.min(100, ratePercent));
        String rateStr = String.format(Locale.US, "%+d%%", ratePercent);

        Log.d(TAG, "Synthesizing: voice=" + voice + " rate=" + rateStr
                + " text=" + text.substring(0, Math.min(50, text.length())) + "...");

        byte[] mp3Data = null;
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (stopRequested) {
                Log.d(TAG, "Stop requested, aborting synthesis");
                callback.error();
                return;
            }

            try {
                mp3Data = EdgeTtsClient.synthesize(text, voice, rateStr);
                if (mp3Data != null && mp3Data.length > 0) {
                    break; // Success!
                }
                Log.w(TAG, "Attempt " + (attempt + 1) + ": empty audio received");
            } catch (Exception e) {
                lastException = e;
                Log.w(TAG, "Attempt " + (attempt + 1) + "/" + MAX_RETRIES
                        + " failed: " + e.getMessage());
            }

            // Wait before retrying (unless this was the last attempt)
            if (attempt < MAX_RETRIES - 1 && !stopRequested) {
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    callback.error();
                    return;
                }
                // Reset clock sync on retry — the GEC token may have expired
                EdgeTtsClient.resetClockSync();
            }
        }

        if (stopRequested) {
            callback.error();
            return;
        }

        if (mp3Data == null || mp3Data.length == 0) {
            Log.e(TAG, "Synthesis failed after " + MAX_RETRIES + " attempts",
                    lastException);
            callback.error();
            return;
        }

        // Decode MP3 → PCM
        Mp3Decoder.PcmResult pcm = Mp3Decoder.decode(mp3Data, getCacheDir());
        mp3Data = null; // Release MP3 buffer immediately

        if (stopRequested || pcm == null || pcm.pcmData.length == 0) {
            callback.error();
            return;
        }

        // Feed PCM to Android TTS framework
        deliverAudio(callback, pcm);
    }

    /**
     * Resolve which voice to use based on the request parameters and preferences.
     */
    private String resolveVoice(SynthesisRequest request) {
        String voice = currentVoice;

        // 1. If a specific voice was requested by name, use it
        String requestedVoiceName = request.getVoiceName();
        if (requestedVoiceName != null && VOICE_GENDERS.containsKey(requestedVoiceName)) {
            return requestedVoiceName;
        }

        // 2. Fallback: check if a specific language was requested
        String lang = request.getLanguage();
        String country = request.getCountry();
        if (lang != null && !lang.isEmpty()) {
            String c = (country == null) ? "" : country;
            String localeStr = lang + (c.isEmpty() ? "" : "-" + c);
            
            if (VOICES.containsKey(localeStr)) {
                if (!currentVoice.startsWith(localeStr)) {
                    voice = VOICES.get(localeStr)[0];
                }
            } else {
                // Try ISO-3 match
                boolean found = false;
                for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
                    String[] parts = entry.getKey().split("-");
                    Locale voiceLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
                    try {
                        if (voiceLocale.getISO3Language().equals(lang)) {
                            if (c.isEmpty() || voiceLocale.getISO3Country().equals(c)) {
                                voice = entry.getValue()[0];
                                found = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                
                // Try ISO-3 language only match
                if (!found) {
                    for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
                        String[] parts = entry.getKey().split("-");
                        Locale voiceLocale = new Locale(parts[0], parts.length > 1 ? parts[1] : "");
                        try {
                            if (voiceLocale.getISO3Language().equals(lang)) {
                                voice = entry.getValue()[0];
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return voice;
    }

    /**
     * Deliver decoded PCM audio to the TTS framework callback.
     * Handles chunking and error recovery.
     */
    private void deliverAudio(SynthesisCallback callback, Mp3Decoder.PcmResult pcm) {
        int maxChunk = callback.getMaxBufferSize();
        callback.start(pcm.sampleRate, AudioFormat.ENCODING_PCM_16BIT, pcm.channelCount);

        int offset = 0;
        while (offset < pcm.pcmData.length && !stopRequested) {
            int bytesToWrite = Math.min(maxChunk, pcm.pcmData.length - offset);
            int result = callback.audioAvailable(pcm.pcmData, offset, bytesToWrite);
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "audioAvailable returned error at offset " + offset);
                break;
            }
            offset += bytesToWrite;
        }

        if (!stopRequested) {
            callback.done();
        }
    }

    // ─── Wake Lock Management ───────────────────────────────────────

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                // 5 minute timeout as safety net — synthesis should never take this long
                wakeLock.acquire(5 * 60 * 1000L);
                Log.d(TAG, "Wake lock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire wake lock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "Wake lock released");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release wake lock: " + e.getMessage());
        }
    }

    // ─── Public API ─────────────────────────────────────────────────

    /**
     * Set the current voice (called from SettingsActivity).
     */
    public void setVoice(String voiceName) {
        this.currentVoice = voiceName;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString("selected_voice", voiceName).apply();
    }
}
