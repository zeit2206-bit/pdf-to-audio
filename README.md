# pdf-to-audio

Turn a PDF book into a narrated audiobook with read-along text, fully offline.

1. **Chunk** (`chunk.py`): split the PDF into chapters from its bookmarks.
2. **Clean** (`clean.py`): remove running headers and footers, figure captions, tables, citations
   and end-of-chapter material, so only the readable text is narrated.
3. **Narrate** (`tts.py`): local neural text-to-speech with [Kokoro](https://github.com/hexgrad/kokoro)
   (ONNX). Numbers are spoken as words; voices rotate per book (`voices.py`, `voice_queue.json`).
4. **Output:** one MP3 per chapter, `.vtt` sentence timestamps, and a read-along HTML page
   (`readalong_template.html`).

`android-app/` is an Android player (Kotlin, Jetpack Compose, Media3) that highlights the current
sentence while it plays. See [android-app/README.md](android-app/README.md).

## Setup

- Python 3.11+, `pip install kokoro-onnx pymupdf pymupdf4llm numpy`.
- Download `kokoro-v1.0.onnx` and `voices-v1.0.bin` into `models/` (not included: large files).
- ffmpeg on the PATH, or set `FFMPEG=<path to ffmpeg>`.

```
python tts.py path/to/book.pdf
```

Books, audio and models are not part of this repository.
