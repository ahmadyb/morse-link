#!/usr/bin/env python3
"""Generates every Morselink logo / launcher-icon asset from scratch.

The mark is two interlocking links — the "link" in Morselink — drawn as clean
outlined rings over the brand green (#1FA36B). No gradients for decoration, no
mascots: fast, minimal, trustworthy, offline-first.

Usage:  python3 tools/make_logo.py     (requires: pip install pillow)
"""

import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_RES = os.path.join(REPO, "app", "src", "main", "res")
ART = os.path.join(REPO, "art")

SS = 4  # supersample factor for anti-aliasing

# §12.1 brand tokens
BG_TOP = (14, 122, 80)      # #0E7A50
BG_BOTTOM = (31, 163, 107)  # #1FA36B
GLYPH = (255, 255, 255, 255)
GLOW = (255, 255, 255)


def render_glyph(size, glyph_ratio=0.62, angle=-18.0):
    """Transparent canvas of `size` px with the Morselink mark centred.

    Two chain links, each rotated away from the other so they read as
    interlocked rather than as one merged capsule.
    """
    canvas = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    s = size * SS
    pad = int(s * 0.25)

    link_w = (s * glyph_ratio) / 1.70
    link_h = link_w * 0.52
    stroke = max(2, int(round(link_w * 0.085)))
    corner = link_h * 0.48
    offset = link_w * 0.34

    def ring(dx, tilt):
        layer = Image.new("RGBA", (s + 2 * pad, s + 2 * pad), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        cx = s / 2.0 + pad + dx
        cy = s / 2.0 + pad
        draw.rounded_rectangle(
            (cx - link_w / 2.0, cy - link_h / 2.0, cx + link_w / 2.0, cy + link_h / 2.0),
            radius=corner,
            outline=GLYPH,
            width=stroke,
        )
        rotated = layer.rotate(tilt, resample=Image.BICUBIC, center=(s / 2.0 + pad, s / 2.0 + pad))
        return rotated.crop((pad, pad, pad + s, pad + s))

    canvas.alpha_composite(ring(-offset, angle))
    canvas.alpha_composite(ring(offset, -angle))

    # Soft glow so the mark holds up on both light and dark surfaces.
    glow = canvas.filter(ImageFilter.GaussianBlur(s * 0.028))
    tinted = Image.new("RGBA", glow.size, GLOW + (0,))
    tinted.putalpha(glow.getchannel("A").point(lambda v: int(v * 0.55)))
    out = Image.alpha_composite(tinted, canvas)

    return out.resize((size, size), Image.LANCZOS)


def gradient(size):
    """Diagonal brand gradient used as the icon background."""
    img = Image.new("RGB", (size, size), BG_TOP)
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        col = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=col)
    return img


def mask_rounded(w, h, radius):
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    return mask


def compose(size, glyph_ratio=0.60, radius_ratio=0.22):
    bg = gradient(size).convert("RGBA")
    bg.putalpha(mask_rounded(size, size, int(size * radius_ratio)))
    glyph = render_glyph(size, glyph_ratio)
    return Image.alpha_composite(bg, glyph)


def banner():
    w, h = 1280, 420
    img = Image.new("RGBA", (w, h), (18, 18, 18, 255))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        col = tuple(int(14 + (10 - 14) * t) for _ in range(3))
        draw.line([(0, y), (w, y)], fill=(col[0], col[0] + 4, col[0] + 2, 255))

    mark = render_glyph(240, glyph_ratio=0.62)
    img.alpha_composite(mark, (70, (h - 240) // 2))

    bold = font(78, bold=True)
    regular = font(28)
    draw.text((360, 150), "Morselink", font=bold, fill=(255, 255, 255, 255))
    draw.text((364, 246), "Fast, offline file sharing", font=regular, fill=(31, 163, 107, 255))
    draw.text((364, 292), "Photos · Videos · Music · Apps · Files", font=font(20),
              fill=(160, 160, 160, 255))
    return img


def font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    path = os.path.join("/usr/share/fonts/truetype/dejavu", name)
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("  ->", os.path.relpath(path, REPO))


def main():
    print("Morselink assets")

    # Adaptive icon foreground (432px; content stays inside the 72dp safe zone).
    save(render_glyph(432, glyph_ratio=0.62),
         os.path.join(ANDROID_RES, "mipmap-anydpi-v26", "ic_launcher_foreground.png"))

    # Legacy launcher PNGs for launchers without adaptive-icon support.
    for folder, size in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                         ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        icon = compose(size, glyph_ratio=0.60, radius_ratio=0.22)
        save(icon, os.path.join(ANDROID_RES, folder, "ic_launcher.png"))
        save(icon, os.path.join(ANDROID_RES, folder, "ic_launcher_round.png"))

    # Store icon and README artwork.
    save(compose(512, glyph_ratio=0.60, radius_ratio=0.22),
         os.path.join(ART, "morselink-play-icon-512.png"))
    save(compose(1024, glyph_ratio=0.60, radius_ratio=0.22),
         os.path.join(ART, "morselink-logo-1024.png"))
    save(render_glyph(1024, glyph_ratio=0.62), os.path.join(ART, "morselink-mark-1024.png"))
    save(banner(), os.path.join(ART, "morselink-banner.png"))

    print("done")


if __name__ == "__main__":
    main()
