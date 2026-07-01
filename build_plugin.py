import hashlib
import json
from pathlib import Path

BASE_URL = "http://127.0.0.1:8000"
IDENTIFIER = "edge-tts-local"

VOICES = [
    ("en-US-AriaNeural", "female", "Aria (US)"),
    ("en-US-JennyNeural", "female", "Jenny (US)"),
    ("en-US-MichelleNeural", "female", "Michelle (US)"),
    ("en-US-AnaNeural", "female", "Ana (US)"),
    ("en-US-GuyNeural", "male", "Guy (US)"),
    ("en-US-ChristopherNeural", "male", "Christopher (US)"),
    ("en-US-EricNeural", "male", "Eric (US)"),
    ("en-US-RogerNeural", "male", "Roger (US)"),
    ("en-US-SteffanNeural", "male", "Steffan (US)"),
    ("en-GB-SoniaNeural", "female", "Sonia (British)"),
    ("en-GB-LibbyNeural", "female", "Libby (British)"),
    ("en-GB-MaisieNeural", "female", "Maisie (British)"),
    ("en-GB-RyanNeural", "male", "Ryan (British)"),
    ("en-GB-ThomasNeural", "male", "Thomas (British)"),
    ("en-AU-NatashaNeural", "female", "Natasha (Australian)"),
    ("en-AU-WilliamNeural", "male", "William (Australian)"),
    ("de-DE-KatjaNeural", "female", "Katja (Deutsch)"),
    ("de-DE-ConradNeural", "male", "Conrad (Deutsch)"),
]

script = Path("koodo_plugin/edgeTTS.js").read_text(encoding="utf-8")

voice_list = [
    {
        "name": name,
        "gender": gender,
        "locale": "-".join(name.split("-")[:2]),
        "displayName": "Edge TTS - " + label,
        "plugin": IDENTIFIER,
        "config": {"baseUrl": BASE_URL, "voiceName": name},
    }
    for name, gender, label in VOICES
]

plugin = {
    "identifier": IDENTIFIER,
    "key": IDENTIFIER,
    "type": "voice",
    "displayName": "Edge TTS Local",
    "icon": "speaker",
    "version": "1.1.0",
    "config": {"baseUrl": BASE_URL},
    "voiceList": voice_list,
    "scriptSHA256": hashlib.sha256(script.encode()).hexdigest(),
    "script": script,
}

Path("koodo_plugin/edgeTTS.json").write_text(
    json.dumps(plugin, ensure_ascii=False, indent=2), encoding="utf-8"
)

print(f"Built: {len(voice_list)} voices, SHA256={plugin['scriptSHA256']}")
