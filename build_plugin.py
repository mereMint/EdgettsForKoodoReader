import json, hashlib

with open('koodo_plugin/edgeTTS.js', 'r', encoding='utf-8') as f:
    script = ' '.join(f.read().split())

plugin = {
    "identifier": "edge-tts-local",
    "type": "voice",
    "displayName": "Edge TTS",
    "icon": "speaker",
    "version": "1.0.0",
    "config": {"baseUrl": "http://127.0.0.1:8000"},
    "voiceList": [],
    "scriptSHA256": hashlib.sha256(script.encode()).hexdigest(),
    "script": script
}

with open('koodo_plugin/edgeTTS.json', 'w', encoding='utf-8') as f:
    json.dump(plugin, f, ensure_ascii=False, indent=2)

print(f"Built: SHA256={plugin['scriptSHA256']}")
