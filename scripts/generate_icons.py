#!/usr/bin/env python3
"""
generate_icons.py — builds all Android launcher icon assets from the SVG
source files, run automatically by GitHub Actions before each build.

Requires: Pillow  (pip install Pillow --break-system-packages)
No other dependencies — parses the two known SVGs directly via simple
rect extraction rather than a full SVG renderer, since the source files
are intentionally simple (flat rounded rects only).

Usage:
    python3 scripts/generate_icons.py
"""

import os
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")

SOURCE_FULL = os.path.join(ROOT, "assets", "icon_source.svg")
SOURCE_FG = os.path.join(ROOT, "assets", "icon_foreground.svg")

SVG_NS = "{http://www.w3.org/2000/svg}"

LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

ADAPTIVE_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def parse_svg_rects(svg_path):
    """Extract background rect (if any) and fill rects from a simple SVG file."""
    tree = ET.parse(svg_path)
    root = tree.getroot()
    viewbox = root.get("viewBox", "0 0 512 512").split()
    vb_w = float(viewbox[2])
    vb_h = float(viewbox[3])

    bg = None
    bars = []

    for elem in root.iter():
        tag = elem.tag.replace(SVG_NS, "")
        if tag == "rect":
            x = float(elem.get("x", 0))
            y = float(elem.get("y", 0))
            w = float(elem.get("width", 0))
            h = float(elem.get("height", 0))
            rx = float(elem.get("rx", 0))
            fill = elem.get("fill", "#000000")

            is_bg = (w >= vb_w * 0.9 and h >= vb_h * 0.9)
            if is_bg:
                bg = {"fill": fill, "rx": rx}
            else:
                bars.append({"x": x, "y": y, "w": w, "h": h, "rx": rx, "fill": fill})
        elif tag == "g":
            fill = elem.get("fill")
            if fill:
                for child in elem:
                    ctag = child.tag.replace(SVG_NS, "")
                    if ctag == "rect":
                        x = float(child.get("x", 0))
                        y = float(child.get("y", 0))
                        w = float(child.get("width", 0))
                        h = float(child.get("height", 0))
                        rx = float(child.get("rx", 0))
                        bars.append({"x": x, "y": y, "w": w, "h": h, "rx": rx, "fill": fill})

    return vb_w, vb_h, bg, bars


def hex_to_rgba(hex_color, alpha=255):
    hex_color = hex_color.lstrip("#")
    r = int(hex_color[0:2], 16)
    g = int(hex_color[2:4], 16)
    b = int(hex_color[4:6], 16)
    return (r, g, b, alpha)


def render(svg_path, size):
    vb_w, vb_h, bg, bars = parse_svg_rects(svg_path)
    scale = size / vb_w

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if bg:
        draw.rounded_rectangle(
            [0, 0, size, size],
            radius=bg["rx"] * scale,
            fill=hex_to_rgba(bg["fill"]),
        )

    for bar in bars:
        x0 = bar["x"] * scale
        y0 = bar["y"] * scale
        x1 = (bar["x"] + bar["w"]) * scale
        y1 = (bar["y"] + bar["h"]) * scale
        draw.rounded_rectangle(
            [x0, y0, x1, y1],
            radius=bar["rx"] * scale,
            fill=hex_to_rgba(bar["fill"]),
        )

    return img


def main():
    if not os.path.exists(SOURCE_FULL) or not os.path.exists(SOURCE_FG):
        raise SystemExit(
            f"ERROR: source SVGs not found.\n"
            f"Expected: {SOURCE_FULL}\n"
            f"      and: {SOURCE_FG}"
        )

    # Legacy launcher icons (square + round), background baked in
    for folder, size in LEGACY_SIZES.items():
        out_dir = os.path.join(RES_DIR, folder)
        os.makedirs(out_dir, exist_ok=True)

        img = render(SOURCE_FULL, size)
        img.save(os.path.join(out_dir, "ic_launcher.png"))

        round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size, size], fill=255)
        round_img.paste(img, (0, 0), mask)
        round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))

        print(f"  wrote {folder}/ic_launcher.png + ic_launcher_round.png ({size}x{size})")

    # Adaptive icon foreground layers (transparent bg, glyph only)
    for folder, size in ADAPTIVE_SIZES.items():
        out_dir = os.path.join(RES_DIR, folder)
        os.makedirs(out_dir, exist_ok=True)

        fg = render(SOURCE_FG, size)
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        print(f"  wrote {folder}/ic_launcher_foreground.png ({size}x{size})")

    # Adaptive icon XML (idempotent — only write if missing)
    anydpi_dir = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)
    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(anydpi_dir, name)
        with open(path, "w") as f:
            f.write(adaptive_xml)
        print(f"  wrote mipmap-anydpi-v26/{name}")

    # Background color value (idempotent)
    values_dir = os.path.join(RES_DIR, "values")
    os.makedirs(values_dir, exist_ok=True)
    color_path = os.path.join(values_dir, "ic_launcher_background.xml")
    if not os.path.exists(color_path):
        with open(color_path, "w") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<resources>\n'
                '    <color name="ic_launcher_background">#0A0A0A</color>\n'
                '</resources>\n'
            )
        print(f"  wrote values/ic_launcher_background.xml")

    print("\nIcon generation complete.")


if __name__ == "__main__":
    main()
    
