#!/usr/bin/env bash
set -euo pipefail
systemctl --user --no-pager -l status edge-tts-koodo.service || true
echo
echo "Health check:"
curl -fsS http://127.0.0.1:8000/health || true
echo
