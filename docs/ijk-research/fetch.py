"""Fetch ijkplayer k0.8.8 source files for Surface/overlay lifecycle research.

raw.githubusercontent.com is blocked here; use ghproxy.net passthrough first,
then fall back to api.github.com contents API (base64).
"""
import base64
import json
import os
import ssl
import urllib.request

TAG = "k0.8.8"
REPO = "bilibili/ijkplayer"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src")
os.makedirs(OUT, exist_ok=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

FILES = [
    "android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java",
    "android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/AbstractMediaPlayer.java",
    "android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IMediaPlayer.java",
    "android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/MediaPlayerProxy.java",
    "ijkmedia/ijkplayer/android/ijkplayer_android.c",
    "ijkmedia/ijkplayer/android/ijkplayer_android_def.h",
    "ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c",
    "ijkmedia/ijkplayer/android/pipeline/ffpipenode_android_mediacodec_vdec.c",
    "ijkmedia/ijkplayer/android/pipeline/ffpipenode_android_mediacodec_vdec.h",
    "ijkmedia/ijksdl/android/ijksdl_vout_android_nativewindow.c",
    "ijkmedia/ijksdl/android/ijksdl_vout_android_nativewindow.h",
    "ijkmedia/ijksdl/android/ijksdl_vout_overlay_android_mediacodec.c",
    "ijkmedia/ijksdl/android/ijksdl_vout_overlay_android_mediacodec.h",
    "ijkmedia/ijksdl/android/ijksdl_codec_android_mediacodec.c",
    "ijkmedia/ijksdl/android/ijksdl_codec_android_mediacodec_java.c",
    "ijkmedia/ijkplayer/ff_ffplay.c",
    "ijkmedia/ijkplayer/ijkplayer.c",
    "ijkmedia/ijksdl/ijksdl_vout.c",
    "ijkmedia/ijksdl/ijksdl_vout.h",
    "ijkmedia/ijkplayer/ijkavformat/ijkavformat.h",
]


def via_proxy(path):
    url = "https://ghproxy.net/https://raw.githubusercontent.com/%s/%s/%s" % (REPO, TAG, path)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(req, timeout=45, context=ctx).read()


def via_api(path):
    url = "https://api.github.com/repos/%s/contents/%s?ref=%s" % (REPO, path, TAG)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = json.loads(urllib.request.urlopen(req, timeout=45, context=ctx).read().decode("utf-8"))
    return base64.b64decode(data["content"])


ok, bad = [], []
for f in FILES:
    dest = os.path.join(OUT, f.replace("/", "__"))
    if os.path.exists(dest) and os.path.getsize(dest) > 500:
        ok.append(("cached", f, os.path.getsize(dest)))
        continue
    raw = None
    for name, fn in (("proxy", via_proxy), ("api", via_api)):
        try:
            raw = fn(f)
            if raw:
                break
        except Exception as e:
            print("  %s failed for %s: %s" % (name, f, e))
    if not raw:
        bad.append(f)
        continue
    with open(dest, "wb") as fh:
        fh.write(raw)
    ok.append(("ok", f, len(raw)))

print("\nFETCH SUMMARY: ok=%d bad=%d" % (len(ok), len(bad)))
for s, f, n in ok:
    print("  [%s] %6d  %s" % (s, n, f))
for f in bad:
    print("  [FAIL] %s" % f)
