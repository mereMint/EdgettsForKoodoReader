package com.edgetts.engine;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

/**
 * Handles ACTION_GET_SAMPLE_TEXT — the Android TTS settings UI calls this
 * to get a sample text string for the currently selected language.
 *
 * Without this Activity, the "Listen to an example" / "Play" button in
 * Android TTS settings won't work for our engine.
 *
 * The activity finishes immediately (invisible) after returning the sample text.
 */
public class GetSampleTextActivity extends Activity {

    private static final String EXTRA_LANGUAGE_KEY = "language";
    private static final String EXTRA_COUNTRY_KEY = "country";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String language = "eng"; // default
        String country = "";

        if (intent != null) {
            String extraLang = intent.getStringExtra(EXTRA_LANGUAGE_KEY);
            String extraCountry = intent.getStringExtra(EXTRA_COUNTRY_KEY);
            if (extraLang != null) {
                language = extraLang;
            }
            if (extraCountry != null) {
                country = extraCountry;
            }
        }

        String sampleText = getSampleText(language, country);

        Intent returnData = new Intent();
        returnData.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText);
        setResult(RESULT_OK, returnData);
        finish();
    }

    private String getSampleText(String language, String country) {
        // Handle both ISO-2 (en) and ISO-3 (eng) codes
        String lang = language.toLowerCase();

        // German
        if (lang.equals("de") || lang.equals("deu")) {
            return "Hallo! Dies ist ein Test der Edge Sprachsynthese. Die Stimme klingt natürlich und klar.";
        }
        // French
        if (lang.equals("fr") || lang.equals("fra")) {
            return "Bonjour ! Ceci est un test de la synthèse vocale Edge. La voix semble naturelle et claire.";
        }
        // Spanish
        if (lang.equals("es") || lang.equals("spa")) {
            return "¡Hola! Esta es una prueba de la síntesis de voz Edge. La voz suena natural y clara.";
        }
        // Italian
        if (lang.equals("it") || lang.equals("ita")) {
            return "Ciao! Questo è un test della sintesi vocale Edge. La voce suona naturale e chiara.";
        }
        // Portuguese
        if (lang.equals("pt") || lang.equals("por")) {
            return "Olá! Este é um teste da síntese de voz Edge. A voz soa natural e clara.";
        }
        // Japanese
        if (lang.equals("ja") || lang.equals("jpn")) {
            return "こんにちは！これはEdge音声合成のテストです。声は自然で明瞭に聞こえます。";
        }
        // Korean
        if (lang.equals("ko") || lang.equals("kor")) {
            return "안녕하세요! 이것은 Edge 음성 합성 테스트입니다. 음성이 자연스럽고 선명하게 들립니다.";
        }
        // Chinese
        if (lang.equals("zh") || lang.equals("zho") || lang.equals("cmn")) {
            return "你好！这是Edge语音合成的测试。声音听起来自然且清晰。";
        }
        // English (default)
        return "Hello! This is a test of the Edge text to speech engine. The voice sounds natural and clear.";
    }
}
