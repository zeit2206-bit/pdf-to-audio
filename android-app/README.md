# Audiobook (Android)

Plays the chapters made by `tts.py` with read-along text, plus a TikTok-style looping video mode.
Design: Figma, page "OTHER TESTS - IGNORE", frame "Audiobook App — Android (Claude design)".

## Install on the phone

1. Copy `../Audiobook.apk` to the phone and open it (allow "install unknown apps" for your file manager once).
2. Copy a book's `*_chunks` folder (the `.mp3` + `.vtt` files) to the phone, e.g. `Audiobooks/`.
3. In the app: Library → **+ Folder** → pick that folder. Videos → **+ Folder** → pick a folder of clips.

## Features

- Library per folder, chapter lengths, progress; position saved per chapter.
- Read-along: current sentence highlighted, auto-scroll (drag to read freely, "Back to current" to resume),
  tap a sentence to jump, speed 0.8×–2×, back 15 s / forward 30 s, next chapter.
- Keeps playing with the screen locked; lock-screen / notification controls (Media3 `PlaybackService`);
  tapping the notification opens the app.
- Videos: vertical swipe (up = next, down = previous, wraps around), each clip loops with sound, tap = pause, hold = 2× while pressed,
  tap or drag the progress bar to seek.
- Silent endings (e.g. the TikTok outro card) are detected once per video and skipped during playback;
  the file is not modified. Toggle with "Cut silent endings" on the Videos screen. Clips keep their shape
  (full width, letterboxed if not vertical); no titles over the video (names only in the grid). The clip's first
  frame covers the gap while the next video loads, so swipes don't flash black.
  Videos play through the same background service, so their sound keeps going with the screen locked or
  in another app (lock-screen controls too). Opening a video replaces the audiobook in the player; the
  chapter resumes from its saved position when reopened. Closing the video feed stops the clip.

## Build

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

- Gradle 8.6 + AGP 8.3.1 + Kotlin 1.9.22, Compose BOM 2024.02, Media3 1.3.1. Needs **Java 21**
  (pinned in `gradle.properties`; Android Studio's bundled Java 25 is too new for Gradle 8.6).
- The release APK is signed with the debug key so it installs directly; use a real key before publishing.
- Paragraph breaks are inferred from the `.vtt` timings (`tts.py` pauses 0.6 s between paragraphs, 0.25 s between sentences).
