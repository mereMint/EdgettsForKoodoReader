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


@app.post("/v1/audio/speech")
async def generate_speech(request: TTSRequest):
    try:
        # Guard against empty text (sent by Koodo at chapter end)
        if not request.text or not request.text.strip():
            return Response(content=b"", media_type="audio/mpeg")

        rate = f"{int((request.speed - 1) * 100):+d}%"
        chunks = split_text_into_chunks(request.text)

        async def audio_stream():
            """Process each text chunk through Edge TTS and yield audio
            fragments sequentially — the client receives one seamless stream."""
            for i, chunk_text in enumerate(chunks):
                try:
                    communicate = edge_tts.Communicate(
                        chunk_text, request.voice, rate=rate
                    )
                    async for msg in communicate.stream():
                        if msg["type"] == "audio":
                            yield msg["data"]
                except Exception as chunk_err:
                    print(f"Chunk {i+1}/{len(chunks)} failed: {chunk_err}")
                    # Skip failed chunks rather than aborting the whole stream
                    continue

        return StreamingResponse(audio_stream(), media_type="audio/mpeg")

    except Exception as e:
        print(f"Error: {e}")
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
