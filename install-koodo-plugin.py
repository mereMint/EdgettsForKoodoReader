#!/usr/bin/env python3
"""Install the Edge TTS plugin directly into Koodo Reader's plugin database.

This avoids Koodo's paste/import dialog losing the plugin on restart.
Close Koodo Reader before running this script.
"""

import json
import shutil
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

HOME = Path.home()
ROOT = Path(__file__).resolve().parent
PLUGIN_JSON = ROOT / "koodo_plugin" / "edgeTTS.json"
DB = HOME / ".config" / "koodo-reader" / "uploads" / "data" / "config" / "plugins.db"


def koodo_is_running() -> bool:
    """Return True only for the real Koodo executable, not this installer.

    pgrep -f is too broad here because it can match this script's source text
    or the shell command that launched it.
    """
    my_pid = str(__import__("os").getpid())
    proc_root = Path("/proc")
    for entry in proc_root.iterdir():
        if not entry.name.isdigit() or entry.name == my_pid:
            continue
        try:
            cmdline = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="ignore")
        except Exception:
            continue
        if cmdline.startswith("/opt/Koodo Reader/koodo-reader"):
            return True
    return False


def main() -> int:
    if koodo_is_running():
        print("ERROR: Koodo Reader is running. Close it first, then rerun this script.", file=sys.stderr)
        return 2

    if not PLUGIN_JSON.exists():
        print(f"ERROR: Missing plugin JSON: {PLUGIN_JSON}", file=sys.stderr)
        return 1

    DB.parent.mkdir(parents=True, exist_ok=True)
    plugin = json.loads(PLUGIN_JSON.read_text(encoding="utf-8"))
    key = plugin.get("key") or plugin["identifier"]

    if DB.exists():
        backup = DB.with_suffix(DB.suffix + ".bak-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
        shutil.copy2(DB, backup)
        print(f"Backed up existing plugin DB to: {backup}")

    con = sqlite3.connect(DB)
    try:
        con.execute(
            """
            CREATE TABLE IF NOT EXISTS plugins (
                key TEXT PRIMARY KEY,
                type TEXT,
                displayName TEXT,
                icon TEXT,
                version TEXT,
                config object,
                autoValue string,
                langList TEXT,
                voiceList TEXT,
                scriptSHA256 TEXT,
                script TEXT
            )
            """
        )
        con.execute(
            """
            INSERT OR REPLACE INTO plugins
            (key, type, displayName, icon, version, config, autoValue, langList, voiceList, scriptSHA256, script)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                key,
                plugin.get("type", "voice"),
                plugin.get("displayName", "Edge TTS Local"),
                plugin.get("icon", "speaker"),
                plugin.get("version", "1.1.0"),
                json.dumps(plugin.get("config", {}), ensure_ascii=False),
                plugin.get("autoValue"),
                json.dumps(plugin.get("langList", []), ensure_ascii=False),
                json.dumps(plugin.get("voiceList", []), ensure_ascii=False),
                plugin.get("scriptSHA256", ""),
                plugin.get("script", ""),
            ),
        )
        con.commit()
    finally:
        con.close()

    print(f"Installed plugin '{key}' into: {DB}")
    print(f"Voices installed: {len(plugin.get('voiceList', []))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
