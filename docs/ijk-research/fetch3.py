"""Round 3: reference example lifecycle + ExoPlayer/Media3 codec-reuse source + issue details."""
import base64
import json
import os
import ssl
import urllib.parse
import urllib.request

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src2")
os.makedirs(OUT, exist_ok=True)
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

# (repo, ref, path, localname)
FILES = [
    ("bilibili/ijkplayer", "k0.8.8",
     "android/ijkplayer/ijkplayer-example/src/main/java/tv/danmaku/ijk/media/example/activities/VideoActivity.java",
     "VideoActivity.java"),
    ("bilibili/ijkplayer", "k0.8.8",
     "android/ijkplayer/ijkplayer-example/src/main/java/tv/danmaku/ijk/media/example/widget/media/IjkVideoView.java",
     "IjkVideoView.java"),
    ("bilibili/ijkplayer", "k0.8.8",
     "android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/MediaPlayerProxy.java",
     "MediaPlayerProxy.java"),
    ("androidx/media", "2.18.1",
     "library/core/src/main/java/com/google/android/exoplayer2/mediacodec/MediaCodecRenderer.java",
     "MediaCodecRenderer_2.18.1.java"),
    ("androidx/media", "1.8.0",
     "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java",
     "MediaCodecRenderer_media3_1.8.0.java"),
    ("androidx/media", "1.8.0",
     "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java",
     "MediaCodecVideoRenderer_media3_1.8.0.java"),
    ("androidx/media", "1.8.0",
     "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecReuseConfig.java",
     "MediaCodecReuseConfig_media3.java"),
]


def raw(repo, ref, path):
    url = "https://ghproxy.net/https://raw.githubusercontent.com/%s/%s/%s" % (repo, ref, path)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(req, timeout=60, context=ctx).read()


def api(repo, ref, path):
    url = "https://api.github.com/repos/%s/contents/%s?ref=%s" % (repo, path, ref)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    d = json.loads(urllib.request.urlopen(req, timeout=60, context=ctx).read().decode("utf-8"))
    return base64.b64decode(d["content"])


print("=== FILES ===")
for repo, ref, path, name in FILES:
    dest = os.path.join(OUT, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 300:
        print("  cached %s" % name)
        continue
    data = None
    for tag, fn in (("proxy", raw), ("api", api)):
        try:
            data = fn(repo, ref, path)
            if data:
                break
        except Exception as e:
            print("   %s fail %s: %s" % (tag, name, e))
    if data:
        open(dest, "wb").write(data)
        print("  ok %7d %s" % (len(data), name))
    else:
        print("  FAIL %s" % name)

ISSUES = [348, 732, 1055, 1324, 1849, 2109, 2107, 3779, 4177, 4683, 4106]
print("\n=== ISSUE DETAILS ===")
dump = []
for n in ISSUES:
    url = "https://api.github.com/repos/bilibili/ijkplayer/issues/%d" % n
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        d = json.loads(urllib.request.urlopen(req, timeout=40, context=ctx).read().decode("utf-8"))
        body = (d.get("body") or "").replace("\r", "")
        dump.append({"number": n, "title": d.get("title"), "state": d.get("state"),
                     "url": d.get("html_url"), "created": d.get("created_at"),
                     "comments": d.get("comments"), "body": body[:2500]})
        print("  #%d [%s] %s (comments=%s)" % (n, d.get("state"), (d.get("title") or "")[:90], d.get("comments")))
    except Exception as e:
        print("  #%d ERROR %s" % (n, e))
json.dump(dump, open(os.path.join(OUT, "issues.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("\nwrote issues.json (%d issues)" % len(dump))
