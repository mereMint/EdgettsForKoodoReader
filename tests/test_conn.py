import asyncio
import aiohttp
import ssl
import certifi
import time
import hashlib
import uuid

def get_sec_ms_gec():
    ticks = time.time()
    ticks += 11644473600
    ticks -= ticks % 300
    ticks *= 10000000
    s = f"{ticks:.0f}6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    return hashlib.sha256(s.encode("ascii")).hexdigest().upper()

async def test():
    gec = get_sec_ms_gec()
    url = f"wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId={uuid.uuid4().hex}&Sec-MS-GEC={gec}&Sec-MS-GEC-Version=1-143.0.3650.75"
    
    headers = {
        "Origin": "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
        "Cookie": f"muid={uuid.uuid4().hex.upper()};",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Accept-Language": "en-US,en;q=0.9",
        "Pragma": "no-cache",
        "Cache-Control": "no-cache"
    }
    
    ctx = ssl.create_default_context(cafile=certifi.where())
    try:
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(url, headers=headers, ssl=ctx) as ws:
                print("Connected successfully!")
    except Exception as e:
        print(f"Failed: {e}")

asyncio.run(test())
