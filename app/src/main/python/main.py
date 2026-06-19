import yt_dlp
import os
import re
import json
import urllib.request

from PIL import Image
from mutagen.mp4 import MP4, MP4Cover


def sanitize(name: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '-', name)
    name = re.sub(r'\s+', ' ', name)   # collapse multiple spaces, keep single ones
    name = name.strip(' -_')
    return name[:150] or "audio"


def best_thumbnail(info: dict) -> str:
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
    try:
        img = Image.open(thumb_path).convert("RGB")
        w, h = img.size
        size = min(w, h)
        left = (w - size) // 2
        top  = (h - size) // 2
        img  = img.crop((left, top, left + size, top + size))
        if size < 800:
            img = img.resize((800, 800), Image.LANCZOS)
        img.save(thumb_path, "JPEG", quality=95)
    except Exception:
        pass


def embed_cover_art(audio_path: str, thumb_path: str, title: str, artist: str) -> None:
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


def _download_single(video_url: str, video_info: dict, download_dir: str) -> str:
    """Download one track into download_dir. Returns final_path or raises."""
    raw_title     = video_info.get("title", "audio")
    title         = sanitize(raw_title)
    artist        = video_info.get("artist") or video_info.get("uploader") or video_info.get("channel") or ""
    thumbnail_url = best_thumbnail(video_info)

    # thumbnail
    thumb_path = ""
    if thumbnail_url:
        try:
            thumb_path = os.path.join(download_dir, f"{title}_thumb.jpg")
            urllib.request.urlretrieve(thumbnail_url, thumb_path)
            crop_to_square(thumb_path)
        except Exception:
            thumb_path = ""

    # audio
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
        dl_info = ydl.extract_info(video_url, download=True)
        if dl_info and "entries" in dl_info:
            entries = [e for e in dl_info["entries"] if e]
            dl_info = entries[0] if entries else dl_info

    ext = dl_info.get("ext", "m4a") if dl_info else "m4a"
    final_path = os.path.join(download_dir, f"{title}.{ext}")

    if not os.path.exists(final_path):
        raise FileNotFoundError(f"File not found after download: {final_path}")

    if ext in ("m4a", "mp4", "m4b") and thumb_path and os.path.exists(thumb_path):
        embed_cover_art(final_path, thumb_path, raw_title, artist)

    if thumb_path and os.path.exists(thumb_path):
        os.remove(thumb_path)

    return final_path


def search_audio(query: str) -> str:
    """Search YouTube for up to 5 results.
    Returns JSON list of {title, artist, thumbnail, url} or 'ERROR: ...'"""
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
    """Download a single track or a full playlist.

    Returns:
      - Single track  → file path string
      - Playlist      → JSON string: {type, folder, name, done, failed, files}
      - Error         → "ERROR: ..."
    """
    try:
        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            flat_info = ydl.extract_info(url, download=False)

        entries = flat_info.get("entries") if flat_info else None
        is_playlist = entries is not None and len(list(entries)) > 1

        # ── PLAYLIST ──────────────────────────────────────────────────────────
        if is_playlist:
            playlist_name = sanitize(flat_info.get("title") or flat_info.get("playlist_title") or "playlist")
            playlist_tmp  = os.path.join(download_dir, playlist_name)
            os.makedirs(playlist_tmp, exist_ok=True)

            # Re-fetch with full info for each entry
            full_opts = {
                "quiet": True,
                "no_warnings": True,
                "extract_flat": False,
                "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            }
            with yt_dlp.YoutubeDL(full_opts) as ydl:
                full_info = ydl.extract_info(url, download=False)

            all_entries = [e for e in (full_info.get("entries") or []) if e]
            done_files  = []
            failed      = 0

            for entry in all_entries:
                try:
                    video_url = entry.get("webpage_url") or entry.get("url") or ""
                    if not video_url:
                        failed += 1
                        continue
                    path = _download_single(video_url, entry, playlist_tmp)
                    done_files.append(path)
                except Exception:
                    failed += 1
                    continue

            return json.dumps({
                "type":   "playlist",
                "folder": playlist_tmp,
                "name":   playlist_name,
                "done":   len(done_files),
                "failed": failed,
                "files":  done_files,
            })

        # ── SINGLE TRACK ─────────────────────────────────────────────────────
        else:
            full_opts = {
                "quiet": True,
                "no_warnings": True,
                "extract_flat": False,
                "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
            }
            with yt_dlp.YoutubeDL(full_opts) as ydl:
                full_info = ydl.extract_info(url, download=False)

            if full_info and "entries" in full_info:
                entries_list = [e for e in full_info["entries"] if e]
                if not entries_list:
                    return "ERROR: No results found."
                full_info = entries_list[0]

            video_url = full_info.get("webpage_url") or full_info.get("url") or url
            return _download_single(video_url, full_info, download_dir)

    except Exception as e:
        return f"ERROR: {str(e)}"
        
