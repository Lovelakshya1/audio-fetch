import yt_dlp
import os
import re
import urllib.request

from mutagen.mp4 import MP4, MP4Cover


def sanitize(name: str) -> str:
    """Make a string safe for use as a filename."""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '-', name)
    name = re.sub(r'\s+', '_', name)
    name = name.strip('-_')
    return name[:150] or "audio"


def resolve_query(text: str) -> str:
    """If text already looks like a URL, pass it through untouched.
    Otherwise treat it as a search term and grab YouTube's top result."""
    text = text.strip()
    if re.match(r'^[a-zA-Z][a-zA-Z0-9+.\-]*://', text):
        return text
    return f"ytsearch1:{text}"


def first_entry(info: dict) -> dict:
    """yt-dlp wraps search/playlist results in an 'entries' list instead of
    returning the video's info dict directly — unwrap it either way."""
    if info and "entries" in info:
        entries = [e for e in info["entries"] if e]
        if not entries:
            raise ValueError("No results found for that search.")
        return entries[0]
    return info


def embed_cover_art(audio_path: str, thumb_path: str, title: str, artist: str) -> None:
    """Tags an .m4a/.mp4 file with cover art + basic metadata using pure-Python
    mutagen — no ffmpeg involved at all. Only works on real MP4-container files
    (m4a/mp4), so callers should only invoke this when ext is m4a/mp4/m4b.
    Best-effort: a tagging failure should never fail the whole download."""
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


def download_audio(url: str, download_dir: str) -> str:
    try:
        query = resolve_query(url)

        # ── 1. Extract metadata without downloading ──────────────────────────
        info_opts = {
            "quiet": True,
            "no_warnings": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        with yt_dlp.YoutubeDL(info_opts) as ydl:
            info = first_entry(ydl.extract_info(query, download=False))

        raw_title = info.get("title", "audio")
        title = sanitize(raw_title)
        artist = info.get("artist") or info.get("uploader") or info.get("channel") or ""
        thumbnail_url = info.get("thumbnail", "")
        video_url = info.get("webpage_url") or info.get("url") or query

        # ── 2. Download thumbnail (best-effort, used for cover art) ──────────
        thumb_path = ""
        if thumbnail_url:
            try:
                thumb_path = os.path.join(download_dir, f"{title}_thumb.jpg")
                urllib.request.urlretrieve(thumbnail_url, thumb_path)
            except Exception:
                thumb_path = ""

        # ── 3. Download audio, m4a/aac preferred (no re-encode needed) ───────
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

        # ── 4. Embed cover art + tags (m4a/mp4 only — pure Python, no ffmpeg) ─
        if ext in ("m4a", "mp4", "m4b") and thumb_path and os.path.exists(thumb_path):
            embed_cover_art(final_path, thumb_path, raw_title, artist)

        if thumb_path and os.path.exists(thumb_path):
            os.remove(thumb_path)

        return final_path
    except Exception as e:
        return f"ERROR: {str(e)}"
        
