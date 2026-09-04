#!/usr/bin/env python3
"""Generates every MorseLink logo / launcher-icon asset from scratch.

The mark is two interlocking chain links: the left one is built from Morse dots,
the right one from Morse dashes, with a soft cyan glow where they connect.

Usage:  python3 tools/make_logo.py
"""

import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_RES = os.path.join(REPO, "app", "src", "main", "res")
ART = os.path.join(REPO, "art")

SS = 4  # supersample factor for anti-aliasing

BG_TOP = (11, 31, 58)      # #0B1F3A
BG_MID = (16, 58, 92)      # #103A5C
BG_BOTTOM = (14, 149, 128)  # #0E9580
GLYPH = (255, 255, 255, 255)
GLOW = (34, 211, 238)      # #22D3EE

# ------------------------------------------------------------------ path maths


def perimeter_points(box, radius, step=0.6):
    """Points along the perimeter of a rounded rectangle, closed loop."""
    x0, y0, x1, y1 = box
    radius = min(radius, (x1 - x0) / 2.0, (y1 - y0) / 2.0)
    pts = []

    def line(a, b):
        length = math.hypot(b[0] - a[0], b[1] - a[1])
        n = max(2, int(length / step))
        for i in range(n):
            t = i / n
            pts.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))

    def arc(cx, cy, r, a0, a1):
        sweep = abs(a1 - a0) / 360.0 * (2 * math.pi * r)
        n = max(3, int(sweep / step))
        for i in range(n):
            a = math.radians(a0 + (a1 - a0) * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))

    line((x0 + radius, y0), (x1 - radius, y0))
    arc(x1 - radius, y0 + radius, radius, -90, 0)
    line((x1, y0 + radius), (x1, y1 - radius))
    arc(x1 - radius, y1 - radius, radius, 0, 90)
    line((x1 - radius, y1), (x0 + radius, y1))
    arc(x0 + radius, y1 - radius, radius, 90, 180)
    line((x0, y1 - radius), (x0, y0 + radius))
    arc(x0 + radius, y0 + radius, radius, 180, 270)
    return pts


def resample(pts, count):
    """Equidistant resampling of a closed point loop."""
    dists = [0.0]
    for i in range(len(pts)):
        p = pts[i]
        q = pts[(i + 1) % len(pts)]
        dists.append(dists[-1] + math.hypot(q[0] - p[0], q[1] - p[1]))
    total = dists[-1]
    out = []
    seg = 0
    for i in range(count):
        target = total * i / count
        while seg < len(pts) - 1 and dists[seg + 1] < target:
            seg += 1
        span = dists[seg + 1] - dists[seg]
        t = 0.0 if span == 0 else (target - dists[seg]) / span
        p = pts[seg]
        q = pts[(seg + 1) % len(pts)]
        out.append((p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
    return out, total


def path_slice(pts, total, start, length, step=0.5):
    n = max(2, int(length / step))
    return [at(pts, total, (start + length * i / (n - 1)) % total) for i in range(n)]


def at(pts, total, distance):
    distance %= total
    acc = 0.0
    for i in range(len(pts)):
        p = pts[i]
        q = pts[(i + 1) % len(pts)]
        seg = math.hypot(q[0] - p[0], q[1] - p[1])
        if acc + seg >= distance:
            t = 0.0 if seg == 0 else (distance - acc) / seg
            return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
        acc += seg
    return pts[0]


def draw_stroke(draw, points, width, fill):
    if len(points) > 1:
        draw.line(points, fill=fill, width=width, joint="curve")
    for p in (points[0], points[-1]):
        r = width / 2.0
        draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=fill)


def draw_link(draw, box, radius, width, dots=0, dashes=0, duty=0.62):
    pts = perimeter_points(box, radius)
    samples, total = resample(pts, 3000)
    if dots:
        spacing = total / dots
        for i in range(dots):
            draw_stroke(draw, [at(samples, total, spacing * (i + 0.5))], width, GLYPH)
    else:
        spacing = total / dashes
        for i in range(dashes):
            start = spacing * (i + (1 - duty) / 2.0)
            draw_stroke(draw, path_slice(samples, total, start, spacing * duty), width, GLYPH)


# ------------------------------------------------------------------ glyph render


def render_glyph(size, glyph_ratio=0.62):
    """Transparent canvas of `size` px with the MorseLink mark centred."""
    canvas = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    s = size * SS
    cx, cy = s / 2.0, s / 2.0

    # `glyph_ratio` is the total width of the *whole* mark, both links included.
    total_w = s * glyph_ratio
    link_w = total_w / 1.64          # the links overlap by ~36% of their width
    link_h = link_w * 0.55
    stroke = link_w * 0.075
    corner = link_h * 0.45
    offset = link_w * 0.32

    left = (cx - offset - link_w / 2.0, cy - link_h / 2.0,
            cx - offset + link_w / 2.0, cy + link_h / 2.0)
    right = (cx + offset - link_w / 2.0, cy - link_h / 2.0,
             cx + offset + link_w / 2.0, cy + link_h / 2.0)

    # Left link is made of Morse dots, right link of Morse dashes.
    draw_link(draw, right, corner, int(round(stroke)), dashes=9)
    draw_link(draw, left, corner, int(round(stroke)), dots=13)

    # Signal node where the two links interlock.
    node = stroke * 0.95
    draw.ellipse([cx - node, cy - node, cx + node, cy + node], fill=GLYPH)

    # Soft cyan glow behind the mark.
    glow = canvas.filter(ImageFilter.GaussianBlur(s * 0.035))
    tinted = Image.new("RGBA", glow.size, GLOW + (0,))
    tinted.putalpha(glow.getchannel("A").point(lambda v: int(v * 0.75)))
    out = Image.alpha_composite(tinted, canvas)

    return out.resize((size, size), Image.LANCZOS)


def gradient(size):
    """Diagonal navy -> teal gradient used as the icon background."""
    img = Image.new("RGB", (size, size), BG_TOP)
    draw = ImageDraw.Draw(img)
    for y in range(size):
        for_x = y / max(1, size - 1)
        if for_x < 0.5:
            t = for_x / 0.5
            col = tuple(int(BG_TOP[i] + (BG_MID[i] - BG_TOP[i]) * t) for i in range(3))
        else:
            t = (for_x - 0.5) / 0.5
            col = tuple(int(BG_MID[i] + (BG_BOTTOM[i] - BG_MID[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=col)
    return img


def mask_rounded(size, radius_ratio):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return mask


def compose(size, glyph_ratio=0.60, radius_ratio=0.22):
    bg = gradient(size).convert("RGBA")
    bg.putalpha(mask_rounded(size, radius_ratio))
    glyph = render_glyph(size, glyph_ratio)
    return Image.alpha_composite(bg, glyph)


# ------------------------------------------------------------------ asset output


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("  ->", os.path.relpath(path, REPO))


def main():
    print("MorseLink assets")

    # Adaptive icon foreground (432px, content inside the 72dp safe zone).
    save(render_glyph(432, glyph_ratio=0.62),
         os.path.join(ANDROID_RES, "mipmap-anydpi-v26", "ic_launcher_foreground.png"))

    # Legacy launcher PNGs (used by launchers that do not support adaptive icons).
    for folder, size in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        icon = compose(size, glyph_ratio=0.60, radius_ratio=0.22)
        save(icon, os.path.join(ANDROID_RES, folder, "ic_launcher.png"))
        save(icon, os.path.join(ANDROID_RES, folder, "ic_launcher_round.png"))

    # In-app monochrome mark (header, about dialog).
    for folder, size in (("drawable-mdpi", 24), ("drawable-hdpi", 36), ("drawable-xhdpi", 48),
                         ("drawable-xxhdpi", 72), ("drawable-xxxhdpi", 96)):
        save(render_glyph(size, glyph_ratio=0.78),
             os.path.join(ANDROID_RES, folder, "ic_logo.png"))

    # Marketing / store artwork.
    save(compose(512, glyph_ratio=0.60, radius_ratio=0.22),
         os.path.join(ART, "morselink-play-icon-512.png"))
    save(compose(1024, glyph_ratio=0.60, radius_ratio=0.22),
         os.path.join(ART, "morselink-logo-1024.png"))
    save(render_glyph(1024, glyph_ratio=0.62), os.path.join(ART, "morselink-mark-1024.png"))
    save(banner(), os.path.join(ART, "morselink-banner.png"))

    print("done")


def banner():
    w, h = 1280, 420
    img = gradient(max(w, h)).crop((0, 0, w, h)).convert("RGBA")
    img.putalpha(mask_rounded_generic(w, h, 34))
    mark = render_glyph(240, glyph_ratio=0.62)
    img.alpha_composite(mark, (70, (h - 240) // 2))

    draw = ImageDraw.Draw(img)
    bold = font(78, bold=True)
    regular = font(28)
    draw.text((360, 150), "MorseLink", font=bold, fill=(255, 255, 255, 255))
    draw.text((364, 244), "Code  ·  Send  ·  Receive", font=regular, fill=(34, 211, 238, 255))
    return img


def mask_rounded_generic(w, h, radius):
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    return mask


def font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    path = os.path.join("/usr/share/fonts/truetype/dejavu", name)
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


if __name__ == "__main__":
    main()
