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
import android.util.Log;

import androidx.core.app.NotificationCompat;
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

        // Create notification channel (required for Android 8.0+)
        createNotificationChannel();

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
        stopForegroundSafely();
        super.onDestroy();
    }

    // ─── Notification / Foreground Service ──────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tts_channel_name),
                    NotificationManager.IMPORTANCE_LOW // Low = no sound, shows in shade
            );
            channel.setDescription(getString(R.string.tts_channel_description));
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        // Tapping the notification opens the settings activity
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.tts_notification_title))
                .setContentText(getString(R.string.tts_notification_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * Promote to foreground service so Android won't kill us when
     * the reader app is closed or backgrounded.
     */
    private void startForegroundSafely() {
        synchronized (foregroundLock) {
            if (isForeground) return;
            try {
                Notification notification = buildNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+: must specify foreground service type
                    startForeground(NOTIFICATION_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                isForeground = true;
                Log.d(TAG, "Promoted to foreground service");
            } catch (Exception e) {
                // If foreground fails (e.g., missing permission on some OEMs),
                // continue anyway — synthesis will still work, just less reliably
                Log.w(TAG, "Failed to start foreground: " + e.getMessage());
            }
        }
    }

    private void stopForegroundSafely() {
        synchronized (foregroundLock) {
            if (!isForeground) return;
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
                isForeground = false;
                Log.d(TAG, "Stopped foreground service");
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop foreground: " + e.getMessage());
            }
        }
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

        // Promote to foreground and acquire wake lock before doing any work
        startForegroundSafely();
        acquireWakeLock();

        try {
            synthesizeWithRetry(request, callback, text);
        } finally {
            releaseWakeLock();
            // Note: We do NOT stop foreground here. The TTS framework may send
            // more utterances immediately after this one. Stopping foreground
            // between utterances would create a window where Android could kill us.
            // The foreground service will be stopped in onDestroy() when the
            // framework unbinds from the service.
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
