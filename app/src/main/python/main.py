import yt_dlp
import os
import re
import json
import urllib.request

from PIL import Image
from mutagen.mp4 import MP4, MP4Cover


def sanitize(name: str) -> str:
    """Make a string safe for use as a filename."""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '-', name)
    name = re.sub(r'\s+', '_', name)
    name = name.strip('-_')
    return name[:150] or "audio"


def is_url(text: str) -> bool:
    return bool(re.match(r'^[a-zA-Z][a-zA-Z0-9+.\-]*://', text.strip()))


def best_thumbnail(info: dict) -> str:
    """Pick highest-resolution thumbnail URL from yt-dlp's thumbnails list."""
    thumbs = info.get("thumbnails") or []
    thumbs_sorted = sorted(
        [t for t in thumbs if t.get("url")],
        key=lambda t: (t.get("width") or t.get("preference") or 0),
        reverse=True
    )
    if thumbs_sorted:
        return thumbs_sorted[0]["url"]
    return info.get("thumbnail", "")


def crop_to_square(thumb_path: str) -> None:
    """Center-crop thumbnail to 1:1 ratio, upscale to 800x800 if small."""
    try:
        img = Image.open(thumb_path).convert("RGB")
        w, h = img.size
        size = min(w, h)
        left = (w - size) // 2
        top = (h - size) // 2
        img = img.crop((left, top, left + size, top + size))
        if size < 800:
            img = img.resize((800, 800), Image.LANCZOS)
        img.save(thumb_path, "JPEG", quality=95)
    except Exception:
        pass


def first_entry(info: dict) -> dict:
    """Unwrap yt-dlp search/playlist result."""
    if info and "entries" in info:
        entries = [e for e in info["entries"] if e]
        if not entries:
            raise ValueError("No results found.")
        return entries[0]
    return info


def embed_cover_art(audio_path: str, thumb_path: str, title: str, artist: str) -> None:
    """Embed cover art + basic metadata into m4a using mutagen."""
    try:
        tags = MP4(audio_path)
        with open(thumb_path, "rb") as f:
            cover_data = f.read()
        tags["covr"] = [MP4Cover(cover_data, imageformat=MP4Cover.FORMAT_JPEG)]
        if title:
            tags["\xa9nam"] = [title]
        if artist:
            tags["\xa9ART"] = [artist]
        tags.save()
    except Exception:
        pass


def search_audio(query: str) -> str:
    """Search YouTube for up to 5 results.
    Returns a JSON string: list of {title, artist, thumbnail, url}
    or "ERROR: ..." on failure."""
    try:
        opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch5:{query}", download=False)

        entries = [e for e in (info.get("entries") or []) if e]
        results = []
        for e in entries[:5]:
            results.append({
                "title":     e.get("title") or "Unknown",
                "artist":    e.get("artist") or e.get("uploader") or e.get("channel") or "",
                "thumbnail": e.get("thumbnail") or "",
                "url":       e.get("url") or e.get("webpage_url") or "",
            })

        return json.dumps(results)
    except Exception as ex:
        return f"ERROR: {str(ex)}"


def download_audio(url: str, download_dir: str) -> str:
    try:
        # ── 1. Extract metadata ───────────────────────────────────────────────
        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            info = first_entry(ydl.extract_info(url, download=False))

        raw_title    = info.get("title", "audio")
        title        = sanitize(raw_title)
        artist       = info.get("artist") or info.get("uploader") or info.get("channel") or ""
        thumbnail_url = best_thumbnail(info)
        video_url    = info.get("webpage_url") or info.get("url") or url

        # ── 2. Download + crop thumbnail ──────────────────────────────────────
        thumb_path = ""
        if thumbnail_url:
            try:
                thumb_path = os.path.join(download_dir, f"{title}_thumb.jpg")
                urllib.request.urlretrieve(thumbnail_url, thumb_path)
                crop_to_square(thumb_path)
            except Exception:
                thumb_path = ""

        # ── 3. Download audio ─────────────────────────────────────────────────
        output_path = os.path.join(download_dir, f"{title}.%(ext)s")
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            "format": "bestaudio[ext=m4a]/bestaudio[ext=aac]/bestaudio",
            "outtmpl": output_path,
            "concurrent_fragment_downloads": 16,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            dl_info = first_entry(ydl.extract_info(video_url, download=True))

        ext = dl_info.get("ext", "m4a")
        final_path = os.path.join(download_dir, f"{title}.{ext}")
        if not os.path.exists(final_path):
            return "ERROR: File not found after download."

        # ── 4. Embed cover art ────────────────────────────────────────────────
        if ext in ("m4a", "mp4", "m4b") and thumb_path and os.path.exists(thumb_path):
            embed_cover_art(final_path, thumb_path, raw_title, artist)

        if thumb_path and os.path.exists(thumb_path):
            os.remove(thumb_path)

        return final_path
    except Exception as e:
        return f"ERROR: {str(e)}"
        
