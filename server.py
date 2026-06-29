"""
Edge TTS Server — Optimised for streaming and low memory usage.

Changes from v1:
  - Audio chunks are written to temp files instead of being accumulated in RAM
  - StreamingResponse serves generated files in bounded chunks and deletes temps
  - Blank/image-only pages return a tiny valid silent audio container immediately
  - GET /health endpoint for connectivity checks
  - GET /voices endpoint to list available voices dynamically

v2 fix:
  - Text chunking: long texts are split into ~2000 char chunks at sentence
    boundaries so Edge TTS WebSocket never receives oversized messages
"""

import re
import shutil
import subprocess
import tempfile
from pathlib import Path
import edge_tts
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, Response
from pydantic import BaseModel
from contextlib import asynccontextmanager

MAX_CHUNK_CHARS = 2000  # Safe limit well under Edge TTS WebSocket max
STREAM_CHUNK_BYTES = 64 * 1024
SILENT_WAV = b"RIFF$\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x80^\x00\x00\x00}\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00"
SILENT_MP3 = (
    b"\xff\xfb\x90\xc4\x00\x00\x00\x00\x00\x00\x00\x00LAME3.100"
    b"UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU"
)


def split_text_into_chunks(text: str, max_chars: int = MAX_CHUNK_CHARS) -> list[str]:
    """Split text into chunks of at most `max_chars`, breaking at natural
    sentence/paragraph boundaries so TTS prosody stays natural.

    Strategy (hierarchical):
      1. Split on paragraph breaks (\\n\\n)
      2. If a paragraph is still too long, split on sentence-ending punctuation
      3. If a sentence is still too long, split on whitespace
      4. Last resort: hard-cut at max_chars
    """
    if len(text) <= max_chars:
        return [text]

    chunks: list[str] = []
    # Split into paragraphs first
    paragraphs = re.split(r'\n\s*\n', text)

    current = ""
    for para in paragraphs:
        para = para.strip()
        if not para:
            continue

        # Would adding this paragraph exceed the limit?
        candidate = (current + "\n\n" + para).strip() if current else para
        if len(candidate) <= max_chars:
            current = candidate
            continue

        # Flush what we have so far
        if current:
            chunks.append(current)
            current = ""

        # If the paragraph itself fits, just use it
        if len(para) <= max_chars:
            current = para
            continue

        # Paragraph too long — split by sentences
        sentences = re.split(r'(?<=[.!?;])\s+', para)
        for sentence in sentences:
            if not sentence.strip():
                continue

            candidate = (current + " " + sentence).strip() if current else sentence
            if len(candidate) <= max_chars:
                current = candidate
                continue

            if current:
                chunks.append(current)
                current = ""

            # If a single sentence fits, use it
            if len(sentence) <= max_chars:
                current = sentence
                continue

            # Sentence too long — split by whitespace
            words = sentence.split()
            for word in words:
                candidate = (current + " " + word) if current else word
                if len(candidate) <= max_chars:
                    current = candidate
                else:
                    if current:
                        chunks.append(current)
                    # Last resort: hard-cut if a single word is enormous
                    if len(word) > max_chars:
                        for i in range(0, len(word), max_chars):
                            chunks.append(word[i:i + max_chars])
                        current = ""
                    else:
                        current = word

    if current:
        chunks.append(current)

    return [c for c in chunks if c.strip()]


@asynccontextmanager
async def lifespan(app):
    print("Edge TTS Server ready at http://127.0.0.1:8000")
    print("No model loading needed — instant startup!")
    yield


app = FastAPI(title="Edge TTS Server", lifespan=lifespan)


class TTSRequest(BaseModel):
    text: str
    voice: str = "en-US-AriaNeural"
    speed: float = 1.0
    format: str = "wav"


@app.get("/health")
async def health():
    """Quick connectivity check for clients."""
    return JSONResponse({"status": "ok"})


@app.get("/voices")
async def list_voices():
    """Return all available Edge TTS voices (cached by edge_tts internally)."""
    voices = await edge_tts.list_voices()
    return JSONResponse(voices)


def clean_text_for_tts(text: str) -> str:
    """Strip HTML tags, entities, and other markup that would break Edge TTS.
    Koodo Reader sends raw HTML page content which Edge TTS interprets as
    broken SSML, causing silent synthesis failures."""
    # Drop image-only markdown before stripping punctuation so cover pages do
    # not synthesize alt text/URLs or send useless requests to Edge TTS.
    clean = re.sub(r'!\[[^\]]*\]\([^)]*\)', ' ', text)
    # Drop embedded data URLs/images that can be huge and are never speakable.
    clean = re.sub(r'data:image/[^\s"\')>]+', ' ', clean, flags=re.IGNORECASE)
    # Remove HTML tags
    clean = re.sub(r'<[^>]+>', ' ', clean)
    # Decode common HTML entities
    clean = clean.replace('&nbsp;', ' ')
    clean = clean.replace('&amp;', '&')
    clean = clean.replace('&lt;', '<')
    clean = clean.replace('&gt;', '>')
    clean = clean.replace('&quot;', '"')
    clean = clean.replace('&#39;', "'")
    clean = clean.replace('&apos;', "'")
    # Remove any remaining HTML entities
    clean = re.sub(r'&[a-zA-Z0-9#]+;', ' ', clean)
    # Collapse whitespace
    clean = re.sub(r'\s+', ' ', clean).strip()
    return clean


def silent_audio_response(output_format: str) -> Response:
    """Return a valid tiny audio file for blank/image-only pages.

    Returning an actual audio container avoids Koodo/Howler retry loops and
    keeps empty pages from touching Edge TTS at all.
    """
    if output_format == "mp3":
        return Response(content=SILENT_MP3, media_type="audio/mpeg")
    return Response(content=SILENT_WAV, media_type="audio/wav")


async def stream_file_and_cleanup(path: Path, cleanup_paths: list[Path]):
    """Yield a file in bounded chunks, then delete all related temp files."""
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(STREAM_CHUNK_BYTES)
                if not chunk:
                    break
                yield chunk
    finally:
        for cleanup_path in cleanup_paths:
            try:
                cleanup_path.unlink(missing_ok=True)
            except Exception as cleanup_err:
                print(f"[TTS] Temp cleanup failed for {cleanup_path}: {cleanup_err}")


def file_stream_response(path: Path, media_type: str, cleanup_paths: list[Path]) -> StreamingResponse:
    """Create a StreamingResponse that does not retain the full audio in RAM."""
    return StreamingResponse(
        stream_file_and_cleanup(path, cleanup_paths),
        media_type=media_type,
    )


def mp3_file_to_wav_file(mp3_path: Path) -> Path:
    """Convert Edge's MP3 output file to a WAV temp file for Koodo on Linux.

    Koodo's Electron/Howler playback is more reliable with WAV on Linux;
    using temp files keeps memory bounded for long pages.
    """
    if not shutil.which("ffmpeg"):
        raise RuntimeError("ffmpeg is required for WAV output. Install it with: sudo apt install ffmpeg")

    wav_file = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    wav_path = Path(wav_file.name)
    wav_file.close()
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(mp3_path),
        "-acodec", "pcm_s16le", "-ac", "1", "-ar", "24000",
        str(wav_path),
    ]
    try:
        subprocess.run(cmd, check=True)
    except Exception:
        wav_path.unlink(missing_ok=True)
        raise
    return wav_path


async def synthesize_mp3_to_tempfile(cleaned_text: str, voice: str, rate: str) -> tuple[Path, int, int]:
    """Write Edge TTS MP3 chunks to a temp file instead of accumulating RAM."""
    chunks = split_text_into_chunks(cleaned_text)
    mp3_file = tempfile.NamedTemporaryFile(suffix=".mp3", delete=False)
    mp3_path = Path(mp3_file.name)
    total_bytes = 0

    try:
        with mp3_file:
            for i, chunk_text in enumerate(chunks):
                try:
                    communicate = edge_tts.Communicate(
                        chunk_text, voice, rate=rate
                    )
                    async for msg in communicate.stream():
                        if msg["type"] == "audio":
                            data = msg["data"]
                            mp3_file.write(data)
                            total_bytes += len(data)
                    print(f"[TTS] Chunk {i+1}/{len(chunks)} OK ({len(chunk_text)} chars)")
                except Exception as chunk_err:
                    print(f"[TTS] Chunk {i+1}/{len(chunks)} FAILED: {chunk_err}")
                    continue
    except Exception:
        mp3_path.unlink(missing_ok=True)
        raise

    if total_bytes == 0:
        mp3_path.unlink(missing_ok=True)
    return mp3_path, len(chunks), total_bytes


@app.post("/v1/audio/speech")
async def generate_speech(request: TTSRequest):
    try:
        output_format = (request.format or "wav").lower()
        if output_format not in {"wav", "mp3"}:
            raise HTTPException(status_code=400, detail="format must be 'wav' or 'mp3'")

        # Guard against empty text (sent by Koodo at chapter end)
        if not request.text or not request.text.strip():
            print("[TTS] Empty text received, returning silent audio")
            return silent_audio_response(output_format)

        # Clean HTML from the text before processing
        cleaned_text = clean_text_for_tts(request.text)
        print(f"[TTS] Received {len(request.text)} chars, cleaned to {len(cleaned_text)} chars")
        print(f"[TTS] Preview: {cleaned_text[:120]}...")

        if not cleaned_text:
            print("[TTS] Text empty after cleaning, returning silent audio")
            return silent_audio_response(output_format)

        rate = f"{int((request.speed - 1) * 100):+d}%"
        chunks = split_text_into_chunks(cleaned_text)
        print(f"[TTS] Split into {len(chunks)} chunks (max {MAX_CHUNK_CHARS} chars each)")

        mp3_path, _, total_bytes = await synthesize_mp3_to_tempfile(cleaned_text, request.voice, rate)
        print(f"[TTS] Total MP3 audio: {total_bytes} bytes")

        if total_bytes == 0:
            print("[TTS] ERROR: No audio data produced! Returning 500")
            raise HTTPException(status_code=500, detail="Edge TTS produced no audio")

        if output_format == "mp3":
            return file_stream_response(mp3_path, "audio/mpeg", [mp3_path])

        try:
            wav_path = mp3_file_to_wav_file(mp3_path)
        except Exception:
            mp3_path.unlink(missing_ok=True)
            raise
        print(f"[TTS] Converted to WAV file: {wav_path.stat().st_size} bytes")
        return file_stream_response(wav_path, "audio/wav", [wav_path, mp3_path])

    except HTTPException:
        raise
    except Exception as e:
        print(f"[TTS] Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import socket

    hostname = socket.gethostname()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "unknown"

    print(f"\n  For your PHONE, use this URL in the plugin config:")
    print(f"  http://{local_ip}:8000\n")

    uvicorn.run(app, host="0.0.0.0", port=8000)
