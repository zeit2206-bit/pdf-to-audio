"""Round-robin voice queue, persisted in voice_queue.json.

The voice at the front is used, then moved to the end, so voices rotate
across chapters, runs and books.
"""
import json
from pathlib import Path

QUEUE_FILE = Path(__file__).with_name("voice_queue.json")

DEFAULT_VOICES = [
    "af_heart", "am_michael", "bf_emma", "am_fenrir", "af_bella",
    "bm_george", "af_nicole", "am_puck", "bm_fable",
]


def load() -> list[str]:
    if QUEUE_FILE.exists():
        return json.loads(QUEUE_FILE.read_text(encoding="utf-8"))
    return list(DEFAULT_VOICES)


def next_voice() -> str:
    """Take the voice at the front of the queue and move it to the end."""
    queue = load()
    voice = queue.pop(0)
    queue.append(voice)
    QUEUE_FILE.write_text(json.dumps(queue, indent=2), encoding="utf-8")
    return voice


def lang_for(voice: str) -> str:
    return "en-gb" if voice.startswith("b") else "en-us"


if __name__ == "__main__":
    print("Next up -> " + " -> ".join(load()))
