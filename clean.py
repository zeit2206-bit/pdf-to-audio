"""PDF -> Markdown (pymupdf4llm) -> plain text ready for TTS.

Usage: python clean.py input.pdf [output.txt]
"""
import re
import sys
from pathlib import Path

import pymupdf4llm

# Page bands to drop (points): running header at y<40, "Access for free" footer at y>750.
MARGINS = (0, 45, 0, 40)

# Section labels that are layout furniture, not narration.
DROP_HEADINGS = {"CHAPTER OUTLINE", "LEARNING OBJECTIVES"}

# "8     1 • Introduction to Psychology" / "1.2 • History of Psychology     9"
# (margins alone don't remove these: pymupdf4llm still emits them).
RUNNING_HEADER = re.compile(r"(?:\d+\s+)?[\d.]+\s+•\s+.+?(?:\s+\d+)?")

CITATION =re.compile(
    r"\s*\((?:[^()]*?(?:\d{4}[a-z]?|n\.d\.|in press)[^()]*?)\)"  # (Sacks, 2007), (Smith & Lee, 2001; Doe, 1999)
)


def to_markdown(pdf: str) -> str:
    return pymupdf4llm.to_markdown(pdf, ignore_images=True, margins=MARGINS)


def clean(md: str) -> str:
    out = []
    skip_list = False  # inside a list that follows a dropped heading
    for line in md.splitlines():
        s = line.strip()
        plain = re.sub(r"[*_#`]", "", s).strip()

        if not s:
            out.append("")
            continue
        if plain.upper() in DROP_HEADINGS:
            skip_list = True
            continue
        if RUNNING_HEADER.fullmatch(plain) or plain.startswith("Access for free at"):
            continue
        if skip_list:
            if s.startswith(("- ", "* ", "• ")) or plain.startswith("By the end of this section"):
                continue
            skip_list = False
        if re.fullmatch(r"\d+", plain):            # lone chapter number
            continue
        if plain.startswith("FIGURE"):              # captions + image credits
            continue
        if "http" in s:                             # "Link to Learning" boxes
            continue
        if s.startswith("|"):                       # tables
            continue
        if re.fullmatch(r"[-|: ]+", s):             # leftover separators
            continue

        # Inline run-in headings like "**INTRODUCTION** Clive..." -> keep the text.
        s = re.sub(r"^\*\*[A-Z][A-Z ]+\*\*\s*", "", s)
        s = re.sub(r"^#+\s*", "", s)                # heading markers
        s = re.sub(r"^[-*•]\s+", "", s)             # bullets
        s = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", s)  # links
        s = re.sub(r"</?\w+>", "", s)               # <u>, <sup>, ...
        s = re.sub(r"[*_`]", "", s)                 # emphasis
        s = CITATION.sub("", s)
        s = re.sub(r"\s+([.,;:!?])", r"\1", s)
        s = re.sub(r"\s{2,}", " ", s).strip()
        if s:
            out.append(s)

    text = "\n".join(out)
    # Rejoin paragraphs split by a page break (line ends without terminal punctuation,
    # next starts lowercase).
    text = re.sub(r"(?<=[^.!?:\"”])\n\n+(?=[a-z])", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


if __name__ == "__main__":
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_suffix(".txt")
    md = to_markdown(str(src))
    src.with_suffix(".md").write_text(md, encoding="utf-8")
    dst.write_text(clean(md), encoding="utf-8")
    print(f"{dst} ({len(dst.read_text(encoding='utf-8').split())} words)")
