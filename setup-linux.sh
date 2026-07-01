#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required. Install it with: sudo apt install python3 python3-venv" >&2
  exit 1
fi

if [ ! -x venv/bin/python ]; then
  echo "[1/3] Creating Python virtual environment..."
  python3 -m venv venv
else
  echo "[1/3] Python virtual environment already exists."
fi

echo "[2/3] Installing Python dependencies..."
venv/bin/python -m pip install --upgrade pip
venv/bin/python -m pip install -r requirements.txt

echo "[3/3] Writing user systemd service..."
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/edge-tts-koodo.service" <<SERVICE
[Unit]
Description=Edge TTS local server for Koodo Reader
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$ROOT
ExecStart=$ROOT/venv/bin/python $ROOT/server.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
SERVICE

systemctl --user daemon-reload

echo
cat <<EOF
Setup complete.

Commands:
  ./start-linux.sh   Start server now and enable it at login
  ./stop-linux.sh    Stop server and disable it at login
  ./status-linux.sh  Show server status and recent logs

Koodo plugin to import:
  $ROOT/koodo_plugin/edgeTTS.json
EOF
