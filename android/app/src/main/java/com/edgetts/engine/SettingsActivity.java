package com.edgetts.engine;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings & test activity for the Edge TTS engine.
 * Allows users to:
 *   - Select their preferred voice
 *   - Test synthesis
 *   - Open Android TTS settings to set Edge TTS as default
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "EdgeTtsSettings";

    // Ordered voice list: display name → voice ID
    private static final LinkedHashMap<String, String> VOICE_MAP = new LinkedHashMap<>();
    static {
        // English (US)
        VOICE_MAP.put("Aria (US Female)", "en-US-AriaNeural");
        VOICE_MAP.put("Jenny (US Female)", "en-US-JennyNeural");
        VOICE_MAP.put("Michelle (US Female)", "en-US-MichelleNeural");
        VOICE_MAP.put("Ana (US Female)", "en-US-AnaNeural");
        VOICE_MAP.put("Guy (US Male)", "en-US-GuyNeural");
        VOICE_MAP.put("Christopher (US Male)", "en-US-ChristopherNeural");
        VOICE_MAP.put("Eric (US Male)", "en-US-EricNeural");
        VOICE_MAP.put("Roger (US Male)", "en-US-RogerNeural");
        VOICE_MAP.put("Steffan (US Male)", "en-US-SteffanNeural");
        // English (GB)
        VOICE_MAP.put("Sonia (British Female)", "en-GB-SoniaNeural");
        VOICE_MAP.put("Libby (British Female)", "en-GB-LibbyNeural");
        VOICE_MAP.put("Maisie (British Female)", "en-GB-MaisieNeural");
        VOICE_MAP.put("Ryan (British Male)", "en-GB-RyanNeural");
        VOICE_MAP.put("Thomas (British Male)", "en-GB-ThomasNeural");
        // English (AU)
        VOICE_MAP.put("Natasha (Australian Female)", "en-AU-NatashaNeural");
        VOICE_MAP.put("William (Australian Male)", "en-AU-WilliamNeural");
        // German
        VOICE_MAP.put("Katja (Deutsch Female)", "de-DE-KatjaNeural");
        VOICE_MAP.put("Conrad (Deutsch Male)", "de-DE-ConradNeural");
        VOICE_MAP.put("Amala (Deutsch Female)", "de-DE-AmalaNeural");
        // French
        VOICE_MAP.put("Denise (French Female)", "fr-FR-DeniseNeural");
        VOICE_MAP.put("Henri (French Male)", "fr-FR-HenriNeural");
        // Spanish
        VOICE_MAP.put("Elvira (Spanish Female)", "es-ES-ElviraNeural");
        VOICE_MAP.put("Alvaro (Spanish Male)", "es-ES-AlvaroNeural");
        // Italian
        VOICE_MAP.put("Elsa (Italian Female)", "it-IT-ElsaNeural");
        VOICE_MAP.put("Isabella (Italian Female)", "it-IT-IsabellaNeural");
        VOICE_MAP.put("Diego (Italian Male)", "it-IT-DiegoNeural");
        // Japanese
        VOICE_MAP.put("Nanami (Japanese Female)", "ja-JP-NanamiNeural");
        VOICE_MAP.put("Keita (Japanese Male)", "ja-JP-KeitaNeural");
        // Chinese
        VOICE_MAP.put("Xiaoxiao (Chinese Female)", "zh-CN-XiaoxiaoNeural");
        VOICE_MAP.put("Yunxi (Chinese Male)", "zh-CN-YunxiNeural");
    }

    private Spinner voiceSpinner;
    private Button testButton;
    private Button openTtsSettingsButton;
    private TextView statusText;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        voiceSpinner = findViewById(R.id.voice_spinner);
        testButton = findViewById(R.id.test_button);
        openTtsSettingsButton = findViewById(R.id.open_tts_settings_button);
        statusText = findViewById(R.id.status_text);

        setupVoiceSpinner();
        setupButtons();
    }

    private void setupVoiceSpinner() {
        List<String> displayNames = new ArrayList<>(VOICE_MAP.keySet());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);

        // Restore saved selection
        String savedVoice = prefs.getString("selected_voice", "en-US-AriaNeural");
        int savedIndex = 0;
        List<String> voiceIds = new ArrayList<>(VOICE_MAP.values());
        for (int i = 0; i < voiceIds.size(); i++) {
            if (voiceIds.get(i).equals(savedVoice)) {
                savedIndex = i;
                break;
            }
        }
        voiceSpinner.setSelection(savedIndex);

        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<String> ids = new ArrayList<>(VOICE_MAP.values());
                String voiceId = ids.get(position);
                prefs.edit().putString("selected_voice", voiceId).apply();
                Log.d(TAG, "Voice selected: " + voiceId);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupButtons() {
        testButton.setOnClickListener(v -> testSynthesis());

        openTtsSettingsButton.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setAction("com.android.settings.TTS_SETTINGS");
            try {
                startActivity(intent);
            } catch (Exception e) {
                // Fallback for devices with different settings paths
                try {
                    intent.setAction(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                } catch (Exception e2) {
                    Toast.makeText(this,
                            "Please open Settings → Accessibility → Text-to-speech manually",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void testSynthesis() {
        testButton.setEnabled(false);
        statusText.setText("Synthesizing...");

        String selectedVoice = prefs.getString("selected_voice", "en-US-AriaNeural");
        String testText = getTestText(selectedVoice);

        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                byte[] mp3 = EdgeTtsClient.synthesize(testText, selectedVoice, "+0%");
                long elapsed = System.currentTimeMillis() - start;

                runOnUiThread(() -> {
                    testButton.setEnabled(true);
                    if (mp3 != null && mp3.length > 0) {
                        statusText.setText(String.format(Locale.US,
                                "✓ Success! %d bytes in %.1fs\nVoice: %s",
                                mp3.length, elapsed / 1000.0, selectedVoice));
                        playMp3(mp3);
                    } else {
                        statusText.setText("✗ Synthesis failed: Empty audio received");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    testButton.setEnabled(true);
                    statusText.setText("✗ Synthesis failed:\n" + e.getMessage());
                });
            }
        });
    }

    private void playMp3(byte[] mp3Data) {
        executor.execute(() -> {
            try {
                java.io.File tempFile = new java.io.File(getCacheDir(), "test_audio.mp3");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(mp3Data);
                }
                android.media.MediaPlayer player = new android.media.MediaPlayer();
                player.setDataSource(tempFile.getAbsolutePath());
                player.prepare();
                player.setOnCompletionListener(mp -> {
                    mp.release();
                    tempFile.delete();
                });
                player.start();
            } catch (Exception e) {
                Log.e(TAG, "Playback failed", e);
            }
        });
    }

    private String getTestText(String voice) {
        if (voice.startsWith("de-")) return "Hallo! Dies ist ein Test der Edge-Sprachsynthese.";
        if (voice.startsWith("fr-")) return "Bonjour! Ceci est un test de la synthèse vocale Edge.";
        if (voice.startsWith("es-")) return "¡Hola! Esta es una prueba de la síntesis de voz Edge.";
        if (voice.startsWith("it-")) return "Ciao! Questo è un test della sintesi vocale Edge.";
        if (voice.startsWith("ja-")) return "こんにちは！これはEdge音声合成のテストです。";
        if (voice.startsWith("zh-")) return "你好！这是Edge语音合成的测试。";
        if (voice.startsWith("ko-")) return "안녕하세요! 이것은 Edge 음성 합성 테스트입니다.";
        if (voice.startsWith("pt-")) return "Olá! Este é um teste da síntese de voz Edge.";
        return "Hello! This is a test of the Edge text-to-speech engine. The quick brown fox jumps over the lazy dog.";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
