package com.edgetts.engine;

import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.HashSet;
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
 * Threading model: onSynthesizeText runs on a dedicated synthesis thread
 * provided by the Android framework. Network calls are safe here.
 */
public class EdgeTtsService extends TextToSpeechService {

    private static final String TAG = "EdgeTtsService";

    // Voice registry: locale → voice names available for that locale
    private static final Map<String, String[]> VOICES = new HashMap<>();
    // Voice name → gender mapping
    private static final Map<String, String> VOICE_GENDERS = new HashMap<>();
    // Set of supported locale strings for fast lookup
    private static final Set<String> SUPPORTED_LOCALES = new HashSet<>();

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

    @Override
    public void onCreate() {
        super.onCreate();
        // Load saved voice preference
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentVoice = prefs.getString("selected_voice", "en-US-AriaNeural");
        Log.i(TAG, "Edge TTS Service created. Voice: " + currentVoice);
    }

    // ─── Language Support ────────────────────────────────────────────

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        String locale = lang + "-" + country;
        if (SUPPORTED_LOCALES.contains(locale)) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        // Check language-only match
        for (String key : SUPPORTED_LOCALES) {
            if (key.startsWith(lang + "-")) {
                return TextToSpeech.LANG_AVAILABLE;
            }
        }
        // Edge TTS supports many more languages — return available for any
        // and let it gracefully fall back
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        String[] parts = currentVoice.split("-");
        if (parts.length >= 2) {
            return new String[]{parts[0], parts[1], ""};
        }
        return new String[]{"en", "US", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
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

        String text = request.getCharSequenceText() != null
                ? request.getCharSequenceText().toString()
                : "";

        if (text.trim().isEmpty()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }

        // Determine voice to use
        String voice = currentVoice;

        // Check if a specific voice was requested via language params
        String lang = request.getLanguage();
        String country = request.getCountry();
        if (lang != null && !lang.isEmpty()) {
            String locale = lang + "-" + country;
            if (VOICES.containsKey(locale)) {
                // Use first voice for that locale if current voice doesn't match
                if (!currentVoice.startsWith(locale)) {
                    voice = VOICES.get(locale)[0];
                }
            } else {
                // Try language-only match
                for (Map.Entry<String, String[]> entry : VOICES.entrySet()) {
                    if (entry.getKey().startsWith(lang + "-")) {
                        voice = entry.getValue()[0];
                        break;
                    }
                }
            }
        }

        // Calculate rate string from speech rate parameter
        // Android passes rate as percentage (100 = normal)
        int speechRate = request.getSpeechRate();
        int ratePercent = speechRate > 0 ? (speechRate - 100) : 0;
        ratePercent = Math.max(-50, Math.min(100, ratePercent));
        String rateStr = String.format(Locale.US, "%+d%%", ratePercent);

        Log.d(TAG, "Synthesizing: voice=" + voice + " rate=" + rateStr
                + " text=" + text.substring(0, Math.min(50, text.length())) + "...");

        // Perform synthesis (this is already on a background thread)
        byte[] mp3Data = EdgeTtsClient.synthesize(text, voice, rateStr);

        if (stopRequested || mp3Data == null) {
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

    /**
     * Set the current voice (called from SettingsActivity).
     */
    public void setVoice(String voiceName) {
        this.currentVoice = voiceName;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putString("selected_voice", voiceName).apply();
    }
}
