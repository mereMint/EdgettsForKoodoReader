package com.edgetts.engine;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Standalone Edge TTS WebSocket client.
 * Connects directly to Microsoft's speech synthesis endpoint —
 * no server or PC required.
 *
 * Protocol:
 *   1. Open WSS connection with output format header
 *   2. Send SSML synthesis request as text frame
 *   3. Receive MP3 audio chunks as binary frames (with 2-byte header offset)
 *   4. "turn.end" text frame signals completion
 *
 * Memory-optimised: streams audio into a pre-sized ByteArrayOutputStream
 * and reuses the OkHttpClient across calls.
 */
public class EdgeTtsClient {

    private static final String TAG = "EdgeTtsClient";
    
    private static long serverTimeOffsetSeconds = 0;
    private static boolean timeSynchronized = false;

    private static java.lang.ref.WeakReference<WebSocket> activeWebSocketRef = null;

    /**
     * Cancels any active WebSocket connection.
     */
    public static synchronized void cancelActiveRequest() {
        if (activeWebSocketRef != null) {
            WebSocket ws = activeWebSocketRef.get();
            if (ws != null) {
                try {
                    ws.cancel();
                    Log.d(TAG, "Active WebSocket request cancelled");
                } catch (Exception e) {
                    Log.w(TAG, "Error cancelling active WebSocket: " + e.getMessage());
                }
            }
            activeWebSocketRef = null;
        }
    }

    // Microsoft's public speech synthesis WebSocket endpoint
    private static final String WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";

    // Output format — 24kHz mono MP3 at 48kbps (compact, good quality)
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    // Shared OkHttp client — connection pooling, reduced GC pressure
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // Binary frame header size: 2 bytes (header length indicator)
    private static final int BINARY_HEADER_SIZE = 2;

    /**
     * Synthesise text to MP3 bytes using the given voice and rate.
     *
     * @param text     The text to synthesise (plain text, will be XML-escaped)
     * @param voice    IETF voice name, e.g. "en-US-AriaNeural"
     * @param rateStr  Rate string, e.g. "+0%", "+20%", "-10%"
     * @return MP3 audio bytes
     * @throws IOException on network or synthesis failure
     */
    public static byte[] synthesize(String text, String voice, String rateStr) throws IOException {
        if (text == null || text.trim().isEmpty()) throw new IOException("Empty text");

        synchronizeClockIfNeeded();

        final String connectionId = UUID.randomUUID().toString().replace("-", "");
        final String requestId = UUID.randomUUID().toString().replace("-", "");
        final String muid = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);

        // Pre-size buffer: ~6KB per second of speech at 48kbps, estimate ~1s per 15 chars
        int estimatedSize = Math.max(4096, (text.length() / 15) * 6000);
        final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream(estimatedSize);
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        final AtomicReference<String> errorRef = new AtomicReference<>();

        String url = WSS_URL
                + "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + generateSecMsGec()
                + "&Sec-MS-GEC-Version=1-143.0.3650.75";

        Request request = new Request.Builder()
                .url(url)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Cookie", "muid=" + muid)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .build();

        WebSocket ws = HTTP_CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // Step 1: Send output format configuration
                String configMsg =
                        "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{"
                        + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\","
                        + "\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
                webSocket.send(configMsg);

                // Step 2: Send SSML synthesis request
                String ssml = buildSsml(text, voice, rateStr);
                String synthMsg =
                        "X-RequestId:" + requestId + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml;
                webSocket.send(synthMsg);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // Text frame — check for turn.end signal
                if (text.contains("turn.end")) {
                    success[0] = true;
                    webSocket.close(1000, "done");
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                // Binary frame — extract MP3 data after the 2-byte header
                byte[] raw = bytes.toByteArray();
                if (raw.length > BINARY_HEADER_SIZE) {
                    // First 2 bytes encode the header length; skip past the text header
                    int headerLen = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
                    int dataOffset = BINARY_HEADER_SIZE + headerLen;
                    if (dataOffset < raw.length) {
                        audioBuffer.write(raw, dataOffset, raw.length - dataOffset);
                    }
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String err = "WebSocket failure: " + t.getMessage();
                if (response != null) {
                    err += " (HTTP " + response.code() + " " + response.message() + ")";
                    try { response.close(); } catch (Exception ignored) {}
                }
                Log.e(TAG, err, t);
                errorRef.set(err);
                latch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (code != 1000 && !success[0]) {
                    errorRef.set("WebSocket closed: " + code + " " + reason);
                }
                latch.countDown();
            }
        });

        synchronized (EdgeTtsClient.class) {
            activeWebSocketRef = new java.lang.ref.WeakReference<>(ws);
        }

        try {
            // Wait up to 30s for synthesis to complete
            if (!latch.await(30, TimeUnit.SECONDS)) {
                Log.w(TAG, "Synthesis timed out");
                ws.cancel();
                throw new IOException("Synthesis timed out after 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.cancel();
            throw new IOException("Synthesis interrupted", e);
        }

        if (!success[0] || audioBuffer.size() == 0) {
            String err = errorRef.get();
            if (err != null) throw new IOException(err);
            throw new IOException("Synthesis failed: No audio received or missing turn.end signal.");
        }

        byte[] result = audioBuffer.toByteArray();
        try { audioBuffer.close(); } catch (IOException ignored) {}
        return result;
    }

    /**
     * Build SSML document for synthesis.
     */
    private static String buildSsml(String text, String voice, String rate) {
        // Extract locale from voice name (e.g., "en-US" from "en-US-AriaNeural")
        String lang = "en-US";
        if (voice != null && voice.contains("-")) {
            String[] parts = voice.split("-");
            if (parts.length >= 2) {
                lang = parts[0] + "-" + parts[1];
            }
        }

        // XML-escape the text to prevent injection
        String escaped = xmlEscape(text);

        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + lang + "'>"
                + "<voice name='" + voice + "'>"
                + "<prosody rate='" + rate + "'>"
                + escaped
                + "</prosody></voice></speak>";
    }

    /**
     * Minimal XML escaping for SSML content.
     */
    private static String xmlEscape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;"); break;
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Generates the Sec-MS-GEC token required by Microsoft's edge TTS endpoint.
     */
    private static String generateSecMsGec() {
        long unixTimeSeconds = (System.currentTimeMillis() / 1000) + serverTimeOffsetSeconds;
        long winEpoch = 11644473600L;
        long ticks = unixTimeSeconds + winEpoch;
        ticks -= ticks % 300; // Round down to 5 minutes
        ticks *= 10000000L;   // Convert to 100-ns intervals
        
        String strToHash = ticks + "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(strToHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Synchronizes the local clock offset using Microsoft's servers to prevent Sec-MS-GEC rejection.
     */
    private static synchronized void synchronizeClockIfNeeded() throws IOException {
        if (timeSynchronized) return;
        try {
            Request request = new Request.Builder()
                    .url("https://bing.com")
                    .head()
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                String dateHeader = response.header("Date");
                
                if (dateHeader != null) {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                    java.util.Date serverDate = sdf.parse(dateHeader);
                    long serverTimeMillis = serverDate.getTime();
                    serverTimeOffsetSeconds = (serverTimeMillis - System.currentTimeMillis()) / 1000;
                    timeSynchronized = true;
                    Log.d(TAG, "Synchronized clock. Offset: " + serverTimeOffsetSeconds + "s");
                } else {
                    // Proceed without sync — local clock might be close enough
                    Log.w(TAG, "No Date header received; proceeding with local clock");
                    timeSynchronized = true;
                    serverTimeOffsetSeconds = 0;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to synchronize clock, proceeding with local time", e);
            // Don't throw — proceed with local clock time. This prevents
            // a temporary network blip from killing the entire synthesis.
            timeSynchronized = true;
            serverTimeOffsetSeconds = 0;
        }
    }

    /**
     * Resets the clock synchronization flag so the next synthesis call
     * will re-sync with the server. Called by EdgeTtsService on retry
     * when synthesis fails (the GEC token may have expired).
     */
    public static synchronized void resetClockSync() {
        timeSynchronized = false;
        Log.d(TAG, "Clock sync reset — will re-sync on next call");
    }
}
