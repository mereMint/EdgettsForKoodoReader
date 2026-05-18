"""
Edge TTS Server — Optimised for streaming and low memory usage.

Changes from v1:
  - StreamingResponse: audio streams to client as it arrives from Edge TTS
    instead of buffering the entire file in RAM first
  - Chunked output: each WebSocket chunk is yielded immediately
  - No BytesIO accumulation: memory stays flat regardless of text length
  - GET /health endpoint for connectivity checks
  - GET /voices endpoint to list available voices dynamically
"""

import edge_tts
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, Response
from pydantic import BaseModel
from contextlib import asynccontextmanager


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
        communicate = edge_tts.Communicate(request.text, request.voice, rate=rate)

        async def audio_stream():
            """Yield audio chunks as they arrive — zero buffering."""
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    yield chunk["data"]

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
