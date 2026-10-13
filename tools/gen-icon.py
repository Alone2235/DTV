# -*- coding: utf-8 -*-
"""
生成抖音TV 应用图标（多分辨率 PNG）
设计：深色圆角底 + 抖音双色音符（青 #25F4EE / 红 #FE2C55），
      两个颜色做轻微错位 = 抖音标志性的色散感。
只依赖 Pillow，图标写成标准 mipmap-* 目录。
"""
import os
from PIL import Image, ImageDraw

RES = r"E:\Android-Builder-MCP\workspace\TVDouyinNative\app\src\main\res"
CYAN = (37, 244, 238)
RED = (254, 44, 85)
BG = (10, 10, 14)

# Android 标准密度档
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

SS = 4  # 超采样倍数（先画大图再缩，边缘才干净）


def note_mask(size, dx=0.0, dy=0.0):
    """音符形状的蒙版：底部圆 + 竖杆 + 顶部小弯"""
    n = size
    m = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(m)
    s = n / 100.0            # 以 100 为设计网格
    ox, oy = dx * s, dy * s

    # 底部圆
    cx, cy, r = 36 * s + ox, 68 * s + oy, 15 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)

    # 竖杆（在圆的右侧）
    x0, x1 = 47 * s + ox, 58 * s + ox
    y0, y1 = 22 * s + oy, 68 * s + oy
    d.rectangle([x0, y0, x1, y1], fill=255)

    # 顶部横弯
    hx0, hx1 = 47 * s + ox, 72 * s + ox
    hy0, hy1 = 18 * s + oy, 30 * s + oy
    d.rounded_rectangle([hx0, hy0, hx1, hy1], radius=6 * s, fill=255)

    return m


def build(size):
    n = size * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 背景：圆角方块 + 轻微竖向渐变
    radius = int(n * 0.21)
    for y in range(n):
        t = y / max(1, n - 1)
        c = (
            int(BG[0] + 12 * t),
            int(BG[1] + 12 * t),
            int(BG[2] + 20 * t),
            255,
        )
        d.line([(0, y), (n, y)], fill=c)
    # 挖出圆角（用 alpha 蒙版）
    bg_mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(bg_mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=radius, fill=255)
    img.putalpha(bg_mask)

    # 音符：青→红错位（色散）
    off = n * 0.012
    img.paste(CYAN + (255,), (0, 0), note_mask(n, dx=-off / (n / 100.0), dy=0))
    img.paste(RED + (255,), (0, 0), note_mask(n, dx=off / (n / 100.0), dy=off / (n / 100.0)))
    # 中间再压一层白色让音符更实
    img.paste((255, 255, 255, 255), (0, 0), note_mask(n, dx=0, dy=0))

    return img.resize((size, size), Image.LANCZOS)


def build_round(size):
    """圆形图标（ic_launcher_round）"""
    sq = build(size)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(sq, (0, 0), mask)
    return out


def build_banner(w=320, h=180):
    """TV 启动器横幅（android:banner）"""
    img = Image.new("RGBA", (w, h), BG + (255,))
    d = ImageDraw.Draw(img)
    # 左侧音符
    icon = build(h - 28)
    img.paste(icon, (14, 14), icon)
    # 右侧文字用简单色块示意（避免依赖字体）
    d.rounded_rectangle([h + 8, h // 2 - 14, h + 8 + 10, h // 2 + 14], radius=4, fill=CYAN)
    d.rounded_rectangle([h + 26, h // 2 - 24, h + 8 + 28, h // 2 + 24], radius=4, fill=RED)
    d.rounded_rectangle([h + 44, h // 2 - 18, h + 8 + 46, h // 2 + 18], radius=4, fill=(255, 255, 255, 200))
    return img


written = []
for folder, size in DENSITIES.items():
    outdir = os.path.join(RES, folder)
    os.makedirs(outdir, exist_ok=True)
    p1 = os.path.join(outdir, "ic_launcher.png")
    build(size).save(p1, "PNG", optimize=True)
    p2 = os.path.join(outdir, "ic_launcher_round.png")
    build_round(size).save(p2, "PNG", optimize=True)
    written += [p1, p2]

# banner 放 drawable
bdir = os.path.join(RES, "drawable")
os.makedirs(bdir, exist_ok=True)
pb = os.path.join(bdir, "tv_banner.png")
build_banner().save(pb, "PNG", optimize=True)
written.append(pb)

for p in written:
    print(f"  {os.path.getsize(p):>7} B  {p.replace(RES + os.sep, '')}")
print(f"共 {len(written)} 个文件")
