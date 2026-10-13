#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频比例修复的常驻校验（canonical check）

用法：  python tools/verify_aspect.py
返回：  0 = 全通过；1 = 有失败项

为什么需要它：
  r1.5 的视频渲染器把 Handler 传 null 时，EventListener 回调会被静默丢弃
  （字节码里 maybeNotifyVideoSizeChanged() 开头就是 `getfield eventHandler;
  ifnull -> skip`）。后果是 AspectFrame 拿不到视频尺寸、aspectRatio 恒为 0，
  画面被 SCALE_TO_FIT 拉伸 —— 而这个错误【编译期完全看不出来】。

  所以把"Handler 实参必须非 null"固化成断言：解析构造调用的实参列表，
  逐槽位比对。以后有人调换参数或改回 null，这里会立刻失败。

断言源码前一律先过 strip_comments()：本仓库的注释会刻意写出"旧写法"
（例如 KDoc 里点名 `value > 0f`），不剥注释就会被自己的文字绊倒。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "douyintv")
GRADLE = os.path.join(ROOT, "app", "build.gradle")

_fails = []


def ck(name, ok, detail=""):
    print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name, ("  <- " + detail) if detail else ""))
    if not ok:
        _fails.append(name)


def strip_comments(text):
    """逐字符剥离注释；字符串字面量里的 '//'（URL 等）原样保留。"""
    out, i, n = [], 0, len(text)
    in_str = in_chr = False
    while i < n:
        c, nxt = text[i], text[i + 1] if i + 1 < n else ""
        if in_str or in_chr:
            out.append(c)
            if c == "\\" and nxt:                       # 转义：吃掉下一个字符
                out.append(nxt)
                i += 2
                continue
            if in_str and c == '"':
                in_str = False
            elif in_chr and c == "'":
                in_chr = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif c == "'":
            in_chr = True
        elif c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        elif c == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(p, code=False):
    with open(p, encoding="utf-8", errors="replace") as f:
        t = f.read()
    return strip_comments(t) if code else t


def call_args(code, callee):
    """取出 callee(...) 的顶层实参列表（忽略括号内的逗号）。"""
    m = re.search(re.escape(callee) + r"\s*\(", code)
    if not m:
        return None
    i, depth, args, cur = m.end(), 1, [], ""
    while i < len(code):
        c = code[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        if c == "," and depth == 1:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return args


print("=" * 68)
print("视频比例修复校验")
print("=" * 68)

eng = read(os.path.join(SRC, "Engines.kt"), code=True)
af = read(os.path.join(SRC, "AspectFrame.kt"), code=True)
pa = read(os.path.join(SRC, "PlayerActivity.kt"), code=True)
gradle = read(GRADLE)
man = read(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"))

print("\n[1] ExoPlayer r1.5 视频渲染器：Handler 实参")
ck("Handler / Looper 已 import",
   "import android.os.Handler" in eng and "import android.os.Looper" in eng)
ck("eventHandler 绑定主线程 Looper",
   "Handler(Looper.getMainLooper())" in eng)
args = call_args(eng, "MediaCodecVideoTrackRenderer")
ck("找到渲染器构造调用", args is not None)
if args:
    ck("实参个数 = 8", len(args) == 8, "实际 %d" % len(args))
    if len(args) == 8:
        ck("槽位 6（Handler）== eventHandler，非 null", args[5] == "eventHandler",
           "= %r" % args[5])
        ck("槽位 7（EventListener）== videoListener", args[6] == "videoListener",
           "= %r" % args[6])

print("\n[2] 视频尺寸换算")
ck("计入 pixelWidthHeightRatio（变形像素）", "pixelWidthHeightRatio > 0f" in eng)
ck("宽高下限保护", "coerceAtLeast(1)" in eng)
ck("90/270 度交换宽高",
   "unappliedRotationDegrees == 90" in eng
   and "unappliedRotationDegrees == 270" in eng
   and "listener?.onVideoSize(h, w)" in eng)

print("\n[3] 比例容器")
ck("aspectRatio 允许归零（不再被 'value > 0f' 拦住）", "value > 0f" not in af)
ck("NaN / 负数归一为 0", "isNaN()" in af)
ck("变更后 requestLayout", "requestLayout()" in af)
ck("onMeasure 内 setMeasuredDimension 约束自身", "setMeasuredDimension" in af)
ck("播放页把比例灌进容器", "applyAspect(ratio)" in pa)
ck("四个比例档位齐全",
   all(k in pa for k in ("ASPECT_AUTO", "ASPECT_FILL", "ASPECT_16_9", "ASPECT_4_3")))

print("\n[4] 交叉引用（本项目历史踩坑点）")
layout_ids = set()
res_dir = os.path.join(ROOT, "app", "src", "main", "res", "layout")
for f in os.listdir(res_dir):
    if f.endswith(".xml"):
        with open(os.path.join(res_dir, f), encoding="utf-8", errors="replace") as fh:
            layout_ids |= set(re.findall(r'@\+?id/([A-Za-z0-9_]+)', fh.read()))
for kt in ("PlayerActivity.kt", "MainActivity.kt", "SettingsActivity.kt",
           "CommentDialogFragment.kt", "ApiProbeActivity.kt", "WhiteTestActivity.kt"):
    p = os.path.join(SRC, kt)
    if not os.path.exists(p):
        ck("%s 存在" % kt, False, "文件缺失")
        continue
    ghost = sorted(i for i in set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", read(p)))
                   if i not in layout_ids)
    ck("%s: R.id.* 全部存在" % kt, not ghost, ", ".join(ghost[:4]))

print("\n[5] 构建配置未漂移")
with open(GRADLE, "rb") as f:
    ck("build.gradle 无 UTF-8 BOM", not f.read(3) == b"\xef\xbb\xbf")
abi = re.search(r"ndk\s*\{([^}]*)\}", gradle)
ck("abiFilters 仅 armeabi-v7a",
   bool(abi) and re.findall(r"'([\w-]+)'", abi.group(1)) == ["armeabi-v7a"])
ck("仅一个 ndk{} 块（重复的会静默覆盖）", gradle.count("ndk {") == 1)
ck("本地 r1.5 aar 仍在依赖中", "exoplayer-r1.5.16" in gradle)
ck("ExoPlayer 2.x 未被引入", "exoplayer-core" not in strip_comments(gradle))
ck("CommentActivity 已从 Manifest 移除", ".CommentActivity" not in man)
ck("ApiProbeActivity 已注册", ".ApiProbeActivity" in man)

print("\n" + "=" * 68)
if _fails:
    print("失败 %d 项：" % len(_fails))
    for n in _fails:
        print("  · " + n)
    sys.exit(1)
print("全部通过")
sys.exit(0)
