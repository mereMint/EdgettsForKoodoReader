# Edge TTS for Koodo Reader

Free, high-quality neural text-to-speech for your books using Microsoft Edge's TTS engine. Sounds natural and human-like with 18+ voice options.

**Completely free** — no API key, no account, no limits.

## Quick Start

1. **Double-click `setup.bat`** — installs in seconds, no model downloads
2. Wait for: `Edge TTS Server ready at http://127.0.0.1:8000`
3. Install the plugin in Koodo Reader (see below)
4. Open a book and enjoy!

> Keep the `setup.bat` window open while reading. Close it when done.

## Installing the Plugin

1. Open **Koodo Reader → Settings → Plugins**
2. Click **"Add custom plugin"**
3. Open `koodo_plugin\edgeTTS.json` in a text editor
4. **Copy everything** and paste into the plugin text box
5. Click **Confirm**

## Available Voices

### American English
| Voice | Gender | Style |
|---|---|---|
| Aria | Female | Conversational, warm (default) |
| Jenny | Female | Friendly, clear |
| Michelle | Female | Professional, calm |
| Ana | Female | Young, bright |
| Guy | Male | Casual, natural |
| Christopher | Male | Professional, steady |
| Eric | Male | Warm, deep |
| Roger | Male | Mature, authoritative |
| Steffan | Male | Energetic, clear |

### British English
| Voice | Gender | Style |
|---|---|---|
| Sonia | Female | Elegant, refined |
| Libby | Female | Warm, natural |
| Maisie | Female | Youthful, bright |
| Ryan | Male | Professional, smooth |
| Thomas | Male | Clear, articulate |

### Australian English
| Voice | Gender | Style |
|---|---|---|
| Natasha | Female | Friendly, warm |
| William | Male | Natural, clear |

### German
| Voice | Gender | Style |
|---|---|---|
| Katja | Female | Clear, professional |
| Conrad | Male | Natural, warm |

## Changing Voices

1. Open a book in Koodo Reader
2. Click the 🎧 TTS icon
3. Select any voice from the dropdown
4. Your choice is remembered

## Using on Android Phone

Your phone can use the same server running on your PC — both must be on the same WiFi.

1. Start `setup.bat` on your PC
2. Note the IP address shown: `For your PHONE, use this URL: http://192.168.x.x:8000`
3. Install **Koodo Reader** on your Android phone
4. Open the `koodo_plugin\edgeTTS.json` file and **change the `baseUrl`** from `http://127.0.0.1:8000` to `http://YOUR_PC_IP:8000` (the IP shown in step 2)
5. Add that modified JSON as a plugin in Koodo Reader on your phone
6. Done! Your phone streams audio from your PC's server

> **Tip:** Your PC's IP might change. If TTS stops working on your phone, check the IP shown in the `setup.bat` window.

## Moving to Another Computer

This folder is fully portable:

1. Copy the entire `tts` folder to your other computer
2. Make sure Python 3.10+ is installed on that computer
3. Delete the `venv` folder
4. Double-click `setup.bat` — it recreates the environment in seconds
5. Re-add the plugin in Koodo Reader

## Troubleshooting

**"Connection refused" in Koodo Reader**
→ Make sure `setup.bat` is running

**No audio plays**
→ Requires internet connection (Edge TTS streams from Microsoft)

**Want to add more voices?**
→ Run `venv\Scripts\python.exe -m edge_tts --list-voices` to see all 400+ available voices, then edit `koodo_plugin\edgeTTS.js`

## Files

| File | Purpose |
|---|---|
| `setup.bat` | One-click setup + start server |
| `server.py` | Local API server (bridges Koodo to Edge TTS) |
| `build_plugin.py` | Rebuilds plugin JSON after editing voices |
| `koodo_plugin/edgeTTS.json` | Plugin to paste into Koodo Reader |
| `koodo_plugin/edgeTTS.js` | Source code (edit to add voices) |
