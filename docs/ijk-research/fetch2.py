"""Fetch remaining ijkplayer internals + query GitHub issues (search API) for known bugs."""
import base64
import json
import os
import ssl
import urllib.parse
import urllib.request

TAG = "k0.8.8"
REPO = "bilibili/ijkplayer"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src")
os.makedirs(OUT, exist_ok=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

FILES = [
    "ijkmedia/ijksdl/android/ijksdl_vout_android_surface.c",
    "ijkmedia/ijksdl/android/ijksdl_vout_android_surface.h",
    "ijkmedia/ijksdl/android/android_nativewindow.c",
    "ijkmedia/ijksdl/android/android_nativewindow.h",
    "ijkmedia/ijksdl/android/ijksdl_android.c",
    "ijkmedia/ijksdl/android/ijksdl_codec_android_mediacodec_java.c",
    "ijkmedia/ijkplayer/ijkplayer_internal.h",
    "ijkmedia/ijksdl/ijksdl_vout_internal.h",
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


print("=== FILE FETCH ===")
for f in FILES:
    dest = os.path.join(OUT, f.replace("/", "__"))
    if os.path.exists(dest) and os.path.getsize(dest) > 300:
        print("  cached %s" % f)
        continue
    raw = None
    for name, fn in (("proxy", via_proxy), ("api", via_api)):
        try:
            raw = fn(f)
            if raw:
                break
        except Exception as e:
            print("   %s fail %s: %s" % (name, f, e))
    if raw:
        open(dest, "wb").write(raw)
        print("  ok %6d %s" % (len(raw), f))
    else:
        print("  FAIL %s" % f)

QUERIES = [
    "repo:bilibili/ijkplayer overlay memory leak",
    "repo:bilibili/ijkplayer setDisplay null surface",
    "repo:bilibili/ijkplayer reset crash MediaCodec_loop",
    "repo:bilibili/ijkplayer mediacodec surface recreate black",
    "repo:bilibili/ijkplayer IjkMediaPlayer reuse setDataSource",
    "repo:bilibili/ijkplayer release surface leak graphics",
]

print("\n=== GITHUB ISSUE SEARCH ===")
for q in QUERIES:
    url = "https://api.github.com/search/issues?per_page=8&q=" + urllib.parse.quote(q)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        data = json.loads(urllib.request.urlopen(req, timeout=40, context=ctx).read().decode("utf-8"))
        print("\n--- %s  (total=%s)" % (q, data.get("total_count")))
        for it in data.get("items", []):
            title = (it.get("title") or "").replace("\n", " ")[:120]
            print("   #%s [%s] %s" % (it["number"], it["state"], title))
            print("      %s" % it["html_url"])
    except Exception as e:
        print("\n--- %s ERROR %s" % (q, e))
