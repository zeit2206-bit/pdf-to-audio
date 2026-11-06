"""Text chunks -> MP3 + sentence timings (.vtt) + read-along page (.html), with Kokoro.

One rotating voice per chapter (see voices.py).

Usage:
  python tts.py book_chunks/*.txt            # each file gets the next voice in the queue
  python tts.py ch01.txt --voice bm_george   # force a voice (queue untouched)
  python tts.py ch01.txt --speed 1.1
"""
import argparse
import html
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

import numpy as np
from kokoro_onnx import Kokoro

import voices

ROOT = Path(__file__).parent
MODELS = ROOT / "models"
SR = 24000
SENTENCE_PAUSE = 0.25
PARAGRAPH_PAUSE = 0.6
REMOTION_FFMPEG = Path(os.environ.get("REMOTION_DIR", "../Remotion"), r"node_modules"
                       r"\@remotion\compositor-win32-x64-msvc\ffmpeg.exe")


def ffmpeg_path() -> str:
    found = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
    if found:
        return found
    if REMOTION_FFMPEG.exists():
        return str(REMOTION_FFMPEG)
    raise SystemExit("ffmpeg not found: install it or set FFMPEG=<path to ffmpeg.exe>")


def split_sentences(paragraph: str) -> list[str]:
    marked = re.sub(r"([.!?…][\"”’)]?)\s+(?=[A-Z0-9\"“(])", "\\1\n", paragraph.strip())
    return [p.strip() for p in marked.split("\n") if p.strip()]


def synthesize(kokoro, text, voice, speed):
    """Return (audio, cues, paragraphs) with cues = [(start, end, text)] per sentence."""
    lang = voices.lang_for(voice)
    chunks, cues, paragraphs, t = [], [], [], 0.0
    for para in (p.strip() for p in text.split("\n\n")):
        if not para:
            continue
        ids = []
        for sentence in split_sentences(para):
            audio, sr = kokoro.create(sentence, voice=voice, speed=speed, lang=lang)
            assert sr == SR
            dur = len(audio) / SR
            ids.append(len(cues))
            cues.append((t, t + dur, sentence))
            chunks += [audio, np.zeros(int(SR * SENTENCE_PAUSE), dtype=np.float32)]
            t += dur + SENTENCE_PAUSE
        chunks.append(np.zeros(int(SR * PARAGRAPH_PAUSE), dtype=np.float32))
        t += PARAGRAPH_PAUSE
        paragraphs.append(ids)
    return np.concatenate(chunks).astype(np.float32), cues, paragraphs


def write_mp3(audio: np.ndarray, dst: Path):
    subprocess.run(
        [ffmpeg_path(), "-y", "-loglevel", "error", "-f", "f32le", "-ar", str(SR), "-ac", "1",
         "-i", "-", "-c:a", "libmp3lame", "-b:a", "64k", str(dst)],
        input=audio.tobytes(), check=True)


def ts(sec: float) -> str:
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{int(h):02d}:{int(m):02d}:{s:06.3f}"


def write_vtt(cues, dst: Path):
    lines = ["WEBVTT", ""]
    for i, (a, b, text) in enumerate(cues, 1):
        lines += [str(i), f"{ts(a)} --> {ts(b)}", text, ""]
    dst.write_text("\n".join(lines), encoding="utf-8")


def write_html(cues, paragraphs, title, voice, mp3_name, dst: Path):
    body = "\n".join(
        "<p>" + " ".join(f'<span data-i="{i}">{html.escape(cues[i][2])}</span>' for i in ids) + "</p>"
        for ids in paragraphs)
    page = (ROOT / "readalong_template.html").read_text(encoding="utf-8")
    page = (page.replace("{{TITLE}}", html.escape(title))
                .replace("{{VOICE}}", html.escape(voice))
                .replace("{{MP3}}", html.escape(mp3_name))
                .replace("{{CUES}}", json.dumps([[round(a, 3), round(b, 3)] for a, b, _ in cues]))
                .replace("{{BODY}}", body))
    dst.write_text(page, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--voice", help="force a voice instead of taking the next in the queue")
    ap.add_argument("--speed", type=float, default=1.0)
    a = ap.parse_args()

    kokoro = Kokoro(str(MODELS / "kokoro-v1.0.onnx"), str(MODELS / "voices-v1.0.bin"))
    for f in map(Path, a.files):
        voice = a.voice or voices.next_voice()
        t = time.time()
        audio, cues, paragraphs = synthesize(kokoro, f.read_text(encoding="utf-8"), voice, a.speed)
        mp3 = f.with_suffix(".mp3")
        write_mp3(audio, mp3)
        write_vtt(cues, f.with_suffix(".vtt"))
        title = re.sub(r"^\d+_", "", f.stem).replace("_", " ").title()
        write_html(cues, paragraphs, title, voice, mp3.name, f.with_suffix(".html"))
        print(f"{mp3.name}: {voice}, {len(audio) / SR / 60:.1f} min audio in "
              f"{(time.time() - t) / 60:.1f} min, {len(cues)} sentences")


if __name__ == "__main__":
    main()
