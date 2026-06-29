import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import server


async def read_response_body(response):
    if hasattr(response, "body_iterator"):
        chunks = []
        async for chunk in response.body_iterator:
            chunks.append(chunk)
        return b"".join(chunks)
    return response.body


class ServerSafetyTests(unittest.TestCase):
    def test_markdown_image_only_page_cleans_to_empty_text(self):
        self.assertEqual(server.clean_text_for_tts("![cover image](cover.png)"), "")
        self.assertEqual(server.clean_text_for_tts("  ![cover](https://example.com/cover.jpg)  "), "")

    def test_empty_wav_request_returns_valid_silent_wav_not_empty_body(self):
        req = server.TTSRequest(text="   ", format="wav")
        response = asyncio.run(server.generate_speech(req))
        body = asyncio.run(read_response_body(response))
        self.assertGreaterEqual(len(body), 44)
        self.assertEqual(body[:4], b"RIFF")
        self.assertIn(b"WAVE", body[:16])
        self.assertEqual(response.media_type, "audio/wav")

    def test_image_only_request_returns_silent_wav_without_contacting_edge_tts(self):
        req = server.TTSRequest(text='<main><img src="cover.jpg" alt="cover"></main>', format="wav")
        with patch.object(server.edge_tts, "Communicate", side_effect=AssertionError("Edge TTS should not be called")):
            response = asyncio.run(server.generate_speech(req))
        body = asyncio.run(read_response_body(response))
        self.assertGreaterEqual(len(body), 44)
        self.assertEqual(body[:4], b"RIFF")
        self.assertEqual(response.media_type, "audio/wav")

    def test_streamed_response_temp_files_are_removed_after_body_is_consumed(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mp3_path = Path(temp_dir) / "speech.mp3"
            wav_path = Path(temp_dir) / "speech.wav"
            mp3_path.write_bytes(b"fake mp3")
            wav_path.write_bytes(server.SILENT_WAV)

            response = server.file_stream_response(
                wav_path,
                media_type="audio/wav",
                cleanup_paths=[wav_path, mp3_path],
            )
            body = asyncio.run(read_response_body(response))

            self.assertEqual(body, server.SILENT_WAV)
            self.assertFalse(wav_path.exists(), "WAV temp file should be deleted after streaming")
            self.assertFalse(mp3_path.exists(), "MP3 temp file should be deleted after streaming")

    def test_mp3_temp_file_is_removed_when_wav_conversion_fails(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            mp3_path = Path(temp_dir) / "speech.mp3"
            mp3_path.write_bytes(b"fake mp3")

            async def fake_synthesize(*_args, **_kwargs):
                return mp3_path, 1, mp3_path.stat().st_size

            with patch.object(server, "synthesize_mp3_to_tempfile", side_effect=fake_synthesize), \
                    patch.object(server, "mp3_file_to_wav_file", side_effect=RuntimeError("ffmpeg failed")):
                with self.assertRaises(Exception):
                    asyncio.run(server.generate_speech(server.TTSRequest(text="real text", format="wav")))

            self.assertFalse(mp3_path.exists(), "MP3 temp file should be removed if WAV conversion fails")


if __name__ == "__main__":
    unittest.main()
