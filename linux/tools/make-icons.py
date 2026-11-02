#!/usr/bin/env python3
"""Makes the app icon's hicolor PNGs (data/icons) from the Mac's square source artwork: the
exit sign as a rounded square with a small margin, as Plasma's app icons are drawn.
Run from linux/: python3 tools/make-icons.py (needs Pillow)."""
from pathlib import Path

from PIL import Image, ImageDraw

SOURCE = Path(__file__).resolve().parents[2] / "apple/tools/icon-source.png"
TARGET = Path(__file__).resolve().parents[1] / "data/icons/hicolor"
SIZES = [16, 22, 24, 32, 48, 64, 128, 256, 512]
SCALE = 4  # drawn larger, then scaled down, for smooth corners

source = Image.open(SOURCE).convert("RGBA")
for size in SIZES:
    big = size * SCALE
    margin = big // 16
    inner = big - 2 * margin
    art = source.resize((inner, inner), Image.LANCZOS)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner - 1, inner - 1), radius=inner // 7, fill=255)
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(art, (margin, margin), mask)
    out = TARGET / f"{size}x{size}/apps/com.takeoutfm.linux.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    icon.resize((size, size), Image.LANCZOS).save(out, optimize=True)
    print(out.relative_to(TARGET.parents[1]))
