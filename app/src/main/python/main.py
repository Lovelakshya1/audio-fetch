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


def _write_progress(download_dir: str, current: int, total: int, track_title: str, status: str = "downloading") -> None:
    """Write progress.json so Kotlin can poll real-time playlist progress."""
    try:
        progress_path = os.path.join(download_dir, "progress.json")
        with open(progress_path, "w") as f:
            json.dump({
                "current": current,
                "total": total,
                "track": track_title,
                "status": status,
            }, f)
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


def manual_thumbnail(video_id: str) -> str:
    """Build a guaranteed-valid YouTube thumbnail URL from a video ID.
    extract_flat doesn't fetch real thumbnails, so we construct it ourselves."""
    if not video_id:
        return ""
    return f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"


def extract_video_id(entry: dict) -> str:
    """Pull the bare video ID from a yt-dlp flat search entry.
    Returns '' if this isn't actually a video (e.g. a channel result)."""
    vid = entry.get("id") or ""
    # Real YouTube video IDs are always exactly 11 chars. Channel IDs (UC...) are 24.
    if vid and len(vid) == 11 and re.match(r'^[0-9A-Za-z_-]{11}$', vid):
        return vid
    url = entry.get("url") or entry.get("webpage_url") or ""
    match = re.search(r'(?:v=|youtu\.be/|shorts/)([0-9A-Za-z_-]{11})(?:$|[?&])', url)
    return match.group(1) if match else ""


def _is_real_video(entry: dict) -> bool:
    """Filter out channels, playlists, and other non-track results."""
    entry_type = (entry.get("_type") or "video").lower()
    if entry_type not in ("video", "url"):
        return False
    # Channel results have ie_key == 'YoutubeTab' or duration is None with no video id
    if entry.get("ie_key") == "YoutubeTab":
        return False
    if not extract_video_id(entry):
        return False
    return True


def _official_score(entry: dict) -> int:
    """Score a search result higher if it looks like an official upload.
    Higher score = more likely official audio/video, ranked first."""
    title = (entry.get("title") or "").lower()
    channel = (entry.get("channel") or entry.get("uploader") or "").lower()

    score = 0

    # Strong positive signals
    if "official audio" in title:
        score += 50
    if "official video" in title:
        score += 45
    if "official music video" in title:
        score += 45
    if channel.endswith("vevo") or "vevo" in channel:
        score += 40
    if entry.get("channel_is_verified"):
        score += 30
    if "topic" in channel:  # "Artist - Topic" auto-generated channels = official audio
        score += 35

    # Negative signals — push down low-quality / unofficial uploads
    if "lyric" in title or "lyrics" in title:
        score -= 10
    if "cover" in title:
        score -= 15
    if "remix" in title and "official" not in title:
        score -= 5
    if "reaction" in title:
        score -= 25
    if "live" in title and "official" not in title:
        score -= 10
    if "8d audio" in title or "slowed" in title or "sped up" in title or "nightcore" in title:
        score -= 20

    return score


def _normalize_for_dedupe(title: str, artist: str) -> str:
    """Build a loose key to detect duplicate uploads of the same track."""
    t = title.lower()
    t = re.sub(r'\(.*?\)|\[.*?\]', '', t)              # strip (Official Video), [HD], etc.
    t = re.sub(r'\bofficial\b|\bmusic\b|\bvideo\b|\baudio\b|\blyrics?\b', '', t)
    t = re.sub(r'[^a-z0-9]+', '', t)                    # strip punctuation/spaces entirely
    return t


def search_audio(query: str) -> str:
    """Search YouTube for up to 15 results, ranked with official audio/video first.
    Returns JSON list of {title, artist, thumbnail, url} or 'ERROR: ...'"""
    try:
        opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "extractor_args": {"youtube": {"player_client": ["tv_embedded"]}},
        }
        # Fetch extra (25) since we'll filter out channels/playlists and dedupe
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch25:{query}", download=False)

        entries = [e for e in (info.get("entries") or []) if e]
        entries = [e for e in entries if _is_real_video(e)]

        # Rank: official audio/video first, ties preserve YouTube's original relevance order
        ranked = sorted(entries, key=lambda e: -_official_score(e))

        results = []
        seen_keys = set()
        for e in ranked:
            if len(results) >= 15:
                break

            title = e.get("title") or "Unknown"
            artist = e.get("artist") or e.get("uploader") or e.get("channel") or ""

            dedupe_key = _normalize_for_dedupe(title, artist)
            if dedupe_key in seen_keys:
                continue
            seen_keys.add(dedupe_key)

            video_id = extract_video_id(e)
            results.append({
                "title":     title,
                "artist":    artist,
                "thumbnail": manual_thumbnail(video_id),
                "url":       e.get("url") or e.get("webpage_url") or (f"https://www.youtube.com/watch?v={video_id}" if video_id else ""),
            })
        return json.dumps(results)
    except Exception as ex:
        return f"ERROR: {str(ex)}"


def get_playlist_info(url: str, download_dir: str) -> str:
    """Quick check: is this a playlist? Returns JSON {is_playlist, name, folder, total} or 'ERROR: ...'.
    Call this BEFORE download_audio so Kotlin knows the folder path to poll for progress."""
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
        entries_list = list(entries) if entries is not None else []
        is_playlist = len(entries_list) > 1

        if is_playlist:
            playlist_name = sanitize(flat_info.get("title") or flat_info.get("playlist_title") or "playlist")
            playlist_tmp  = os.path.join(download_dir, playlist_name)
            return json.dumps({
                "is_playlist": True,
                "name": playlist_name,
                "folder": playlist_tmp,
                "total": len(entries_list),
            })
        else:
            return json.dumps({"is_playlist": False})
    except Exception as e:
        return f"ERROR: {str(e)}"


def get_progress(folder: str) -> str:
    """Read progress.json from a playlist folder. Returns JSON string or '{}' if not found yet."""
    try:
        progress_path = os.path.join(folder, "progress.json")
        if not os.path.exists(progress_path):
            return "{}"
        with open(progress_path, "r") as f:
            return f.read()
    except Exception:
        return "{}"


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
            total_tracks = len(all_entries)
            done_files  = []
            failed      = 0

            _write_progress(playlist_tmp, 0, total_tracks, "", "starting")

            for idx, entry in enumerate(all_entries, start=1):
                track_title = entry.get("title") or "track"
                _write_progress(playlist_tmp, idx, total_tracks, track_title, "downloading")
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

            _write_progress(playlist_tmp, total_tracks, total_tracks, "", "done")

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
    
