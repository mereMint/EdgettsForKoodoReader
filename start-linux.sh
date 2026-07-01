#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE=edge-tts-koodo.service

if [ ! -x "$ROOT/venv/bin/python" ]; then
  "$ROOT/setup-linux.sh"
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg is required for Koodo WAV playback." >&2
  echo "Install it with: sudo apt install ffmpeg" >&2
  exit 1
fi

systemctl --user enable --now "$SERVICE"
sleep 1

if curl -fsS http://127.0.0.1:8000/health >/dev/null; then
  echo "Edge TTS server is running at http://127.0.0.1:8000"
  echo "Import this in Koodo Reader: $ROOT/koodo_plugin/edgeTTS.json"
else
  echo "Server did not answer health check. Showing logs:" >&2
  systemctl --user --no-pager -l status "$SERVICE" >&2 || true
  exit 1
fi
