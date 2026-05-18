import io
import edge_tts
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
from contextlib import asynccontextmanager

@asynccontextmanager
async def lifespan(app):
    print("Edge TTS Server ready at http://127.0.0.1:8000")
    print("No model loading needed - instant startup!")
    yield

app = FastAPI(title="Edge TTS Server", lifespan=lifespan)

class TTSRequest(BaseModel):
    text: str
    voice: str = "en-US-AriaNeural"
    speed: float = 1.0

@app.post("/v1/audio/speech")
async def generate_speech(request: TTSRequest):
    try:
        rate = f"{int((request.speed - 1) * 100):+d}%"
        communicate = edge_tts.Communicate(request.text, request.voice, rate=rate)
        buf = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                buf.write(chunk["data"])
        buf.seek(0)
        return Response(content=buf.read(), media_type="audio/mpeg")
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
