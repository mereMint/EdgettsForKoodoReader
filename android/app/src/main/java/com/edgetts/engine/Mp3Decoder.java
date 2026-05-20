package com.edgetts.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodes MP3 bytes to 16-bit PCM using Android's built-in MediaCodec.
 * Zero external dependencies — uses the hardware-accelerated decoder
 * already present on every Android device.
 *
 * Memory strategy:
 *   - Writes MP3 to a temp file (avoids holding two copies in RAM)
 *   - Streams PCM into a growing buffer with 2x capacity growth
 *   - Cleans up temp file immediately after decoding
 */
public class Mp3Decoder {

    private static final String TAG = "Mp3Decoder";
    private static final long TIMEOUT_US = 10_000; // 10ms codec timeout

    /**
     * Decoded PCM result containing raw audio and its format parameters.
     */
    public static class PcmResult {
        public final byte[] pcmData;
        public final int sampleRate;
        public final int channelCount;

        public PcmResult(byte[] pcmData, int sampleRate, int channelCount) {
            this.pcmData = pcmData;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }

    /**
     * Decode MP3 bytes to 16-bit PCM.
     *
     * @param mp3Bytes Raw MP3 data
     * @param cacheDir Directory for temporary files (use context.getCacheDir())
     * @return PcmResult with decoded audio, or null on failure
     */
    public static PcmResult decode(byte[] mp3Bytes, File cacheDir) {
        if (mp3Bytes == null || mp3Bytes.length == 0) return null;

        File tempFile = null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;

        try {
            // Write MP3 to temp file for MediaExtractor
            tempFile = new File(cacheDir, "tts_temp_" + Thread.currentThread().getId() + ".mp3");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(mp3Bytes);
            }

            // Set up extractor
            extractor = new MediaExtractor();
            extractor.setDataSource(tempFile.getAbsolutePath());

            if (extractor.getTrackCount() == 0) {
                Log.e(TAG, "No tracks found in MP3");
                return null;
            }

            extractor.selectTrack(0);
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            // Configure codec
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // Pre-size PCM buffer: MP3 at 48kbps → ~10x expansion to 16-bit PCM
            int estimatedPcmSize = mp3Bytes.length * 12;
            byte[] pcmBuffer = new byte[estimatedPcmSize];
            int pcmOffset = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain output
                int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        // Grow buffer if needed
                        if (pcmOffset + info.size > pcmBuffer.length) {
                            byte[] newBuf = new byte[Math.max(pcmBuffer.length * 2,
                                    pcmOffset + info.size)];
                            System.arraycopy(pcmBuffer, 0, newBuf, 0, pcmOffset);
                            pcmBuffer = newBuf;
                        }
                        outputBuffer.get(pcmBuffer, pcmOffset, info.size);
                        pcmOffset += info.size;
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Update format if codec reports a change
                    MediaFormat newFormat = codec.getOutputFormat();
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }

            // Trim to exact size
            byte[] result = new byte[pcmOffset];
            System.arraycopy(pcmBuffer, 0, result, 0, pcmOffset);

            return new PcmResult(result, sampleRate, channelCount);

        } catch (Exception e) {
            Log.e(TAG, "Decode failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
