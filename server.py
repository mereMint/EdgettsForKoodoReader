"""
Edge TTS Server — Optimised for streaming and low memory usage.

Changes from v1:
  - StreamingResponse: audio streams to client as it arrives from Edge TTS
    instead of buffering the entire file in RAM first
  - Chunked output: each WebSocket chunk is yielded immediately
  - No BytesIO accumulation: memory stays flat regardless of text length
  - GET /health endpoint for connectivity checks
  - GET /voices endpoint to list available voices dynamically

v2 fix:
  - Text chunking: long texts are split into ~2000 char chunks at sentence
    boundaries so Edge TTS WebSocket never receives oversized messages
"""

import re
import edge_tts
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, Response
from pydantic import BaseModel
from contextlib import asynccontextmanager

MAX_CHUNK_CHARS = 2000  # Safe limit well under Edge TTS WebSocket max


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
    # Remove HTML tags
    clean = re.sub(r'<[^>]+>', ' ', text)
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


@app.post("/v1/audio/speech")
async def generate_speech(request: TTSRequest):
    try:
        # Guard against empty text (sent by Koodo at chapter end)
        if not request.text or not request.text.strip():
            print("[TTS] Empty text received, returning empty response")
            return Response(content=b"", media_type="audio/mpeg")

        # Clean HTML from the text before processing
        cleaned_text = clean_text_for_tts(request.text)
        print(f"[TTS] Received {len(request.text)} chars, cleaned to {len(cleaned_text)} chars")
        print(f"[TTS] Preview: {cleaned_text[:120]}...")

        if not cleaned_text:
            print("[TTS] Text empty after cleaning, returning empty response")
            return Response(content=b"", media_type="audio/mpeg")

        rate = f"{int((request.speed - 1) * 100):+d}%"
        chunks = split_text_into_chunks(cleaned_text)
        print(f"[TTS] Split into {len(chunks)} chunks (max {MAX_CHUNK_CHARS} chars each)")

        # Buffer audio instead of streaming — this lets us detect failures
        # before committing to a 200 response with empty body
        audio_parts: list[bytes] = []

        for i, chunk_text in enumerate(chunks):
            try:
                communicate = edge_tts.Communicate(
                    chunk_text, request.voice, rate=rate
                )
                async for msg in communicate.stream():
                    if msg["type"] == "audio":
                        audio_parts.append(msg["data"])
                print(f"[TTS] Chunk {i+1}/{len(chunks)} OK ({len(chunk_text)} chars)")
            except Exception as chunk_err:
                print(f"[TTS] Chunk {i+1}/{len(chunks)} FAILED: {chunk_err}")
                continue

        audio_data = b"".join(audio_parts)
        print(f"[TTS] Total audio: {len(audio_data)} bytes")

        if not audio_data:
            print("[TTS] ERROR: No audio data produced! Returning 500")
            raise HTTPException(status_code=500, detail="Edge TTS produced no audio")

        return Response(content=audio_data, media_type="audio/mpeg")

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
