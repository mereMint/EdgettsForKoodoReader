package com.edgetts.engine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles ACTION_CHECK_TTS_DATA — the Android TTS framework calls this
 * to discover which voices/languages are available from this engine.
 *
 * Without this Activity, Android Settings and apps like Koodo Reader
 * cannot enumerate or select our voices.
 *
 * The activity finishes immediately (invisible) after returning the
 * voice data via setResult().
 */
public class CheckVoiceDataActivity extends Activity {

    private static final String EXTRA_AVAILABLE_LANGUAGES_KEY = "availableLanguages";
    private static final String EXTRA_UNAVAILABLE_LANGUAGES_KEY = "unavailableLanguages";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ArrayList<String> availableVoices = new ArrayList<>();
        ArrayList<String> unavailableVoices = new ArrayList<>();
        Set<String> availableLanguagesSet = new HashSet<>();

        for (Map.Entry<String, String[]> entry : EdgeTtsService.VOICES.entrySet()) {
            String localeStr = entry.getKey(); // e.g. "en-US"
            String[] parts = localeStr.split("-");
            java.util.Locale locale = new java.util.Locale(
                    parts[0], parts.length > 1 ? parts[1] : "");

            try {
                String iso3Lang = locale.getISO3Language();
                String iso3Country = locale.getISO3Country();
                String iso3Locale = iso3Lang
                        + (iso3Country.isEmpty() ? "" : "-" + iso3Country);
                availableLanguagesSet.add(iso3Locale);
                availableLanguagesSet.add(iso3Lang);
            } catch (Exception ignored) {}

            // Also add in ISO-2 format (e.g. "en-US") for broader compatibility
            availableLanguagesSet.add(localeStr);
            if (!locale.getLanguage().isEmpty()) {
                availableLanguagesSet.add(locale.getLanguage());
            }

            // Add each individual voice name as available
            for (String voiceName : entry.getValue()) {
                availableVoices.add(voiceName);
            }
        }

        Intent returnData = new Intent();

        ArrayList<String> availableLanguages = new ArrayList<>(availableLanguagesSet);

        // EXTRA_AVAILABLE_LANGUAGES: list of available language locales
        returnData.putStringArrayListExtra(
            EXTRA_AVAILABLE_LANGUAGES_KEY, availableLanguages);

        // EXTRA_UNAVAILABLE_LANGUAGES: empty — all languages are always available
        returnData.putStringArrayListExtra(
            EXTRA_UNAVAILABLE_LANGUAGES_KEY, new ArrayList<>());

        // EXTRA_AVAILABLE_VOICES: voice names only
        returnData.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices);

        // EXTRA_UNAVAILABLE_VOICES: empty — all voices are always available
        returnData.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices);

        // Return CHECK_VOICE_DATA_PASS — all data is present (no download needed)
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData);
        finish();
    }
}
