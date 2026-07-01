#!/usr/bin/env bash
set -euo pipefail
systemctl --user disable --now edge-tts-koodo.service || true
echo "Edge TTS Koodo service stopped and disabled."
