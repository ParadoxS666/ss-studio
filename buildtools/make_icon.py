"""Draws the adaptive launcher icon layers (432x432 = 108dp @ xxxhdpi)."""
import sys

from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
S = 432
SS = 4  # supersampling


def big(n):
    return int(round(n * SS))


def background():
    w = S * SS
    img = Image.new("RGB", (w, w))
    px = img.load()
    top, bot = (40, 33, 27), (16, 14, 13)
    for y in range(w):
        t = y / (w - 1)
        c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    # warm glow behind the keys
    glow = Image.new("L", (w, w), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w * 0.18, w * 0.30, w * 0.82, w * 0.92], fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.09))
    amber = Image.new("RGB", (w, w), (255, 159, 67))
    img = Image.composite(amber, img, glow.point(lambda v: int(v * 0.35)))
    return img.resize((S, S), Image.LANCZOS)


def keys(mono=False):
    w = S * SS
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # keyboard block inside the 66dp safe zone (centre 264 px)
    kw, kh = 46, 150          # white key size (px @432)
    gap = 6
    n = 4
    total = n * kw + (n - 1) * gap
    x0 = (S - total) / 2
    y0 = (S - kh) / 2 + 8
    white = (255, 255, 255, 255) if mono else (242, 238, 232, 255)
    lit = (255, 255, 255, 255) if mono else (255, 159, 67, 255)
    for i in range(n):
        x = x0 + i * (kw + gap)
        col = lit if i == 2 else white
        if mono and i == 2:
            col = (255, 255, 255, 170)
        d.rounded_rectangle([big(x), big(y0), big(x + kw), big(y0 + kh)], radius=big(9), fill=col)
    # black keys between whites 0-1, 1-2, 3-... (C# D# F# pattern on 4 keys: between 0-1, 1-2)
    bw, bh = 30, 92
    black = (0, 0, 0, 0) if mono else (24, 22, 21, 255)
    for i in (0, 1):
        cx = x0 + (i + 1) * (kw + gap) - gap / 2
        rect = [big(cx - bw / 2), big(y0 - 2), big(cx + bw / 2), big(y0 + bh)]
        if mono:
            # cut the black keys out of the white shape
            d.rounded_rectangle([rect[0] - big(gap / 2), rect[1], rect[2] + big(gap / 2), rect[3] + big(gap / 2)],
                                radius=big(7), fill=(0, 0, 0, 0))
        else:
            d.rounded_rectangle([rect[0] - big(gap / 2), rect[1], rect[2] + big(gap / 2), rect[3] + big(gap / 2)],
                                radius=big(7), fill=(0, 0, 0, 0))
            d.rounded_rectangle(rect, radius=big(6), fill=black)
    cx = x0 + 4 * (kw + gap) - gap / 2  # black key hanging off the right edge is skipped
    return img.resize((S, S), Image.LANCZOS)


background().save(f"{OUT}/ic_bg.png", optimize=True)
keys().save(f"{OUT}/ic_fg.png", optimize=True)
keys(mono=True).save(f"{OUT}/ic_mono.png", optimize=True)
# preview: composited with a circular mask, like the Pixel launcher
bg = background().convert("RGBA")
fg = keys()
comp = Image.alpha_composite(bg, fg)
mask = Image.new("L", (S * 2, S * 2), 0)
ImageDraw.Draw(mask).ellipse([S * 2 * 0.17, S * 2 * 0.17, S * 2 * 0.83, S * 2 * 0.83], fill=255)
mask = mask.resize((S, S), Image.LANCZOS)
prev = Image.new("RGBA", (S, S), (255, 255, 255, 255))
prev.paste(comp, (0, 0), mask)
prev.save(f"{OUT}/preview.png")
