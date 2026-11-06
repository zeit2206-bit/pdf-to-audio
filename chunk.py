"""Split a PDF into per-chapter, TTS-ready text chunks using its bookmarks (TOC).

End-of-chapter material (questions, key terms, ...) and front/back matter are
dropped unless explicitly included.

Usage:
  python chunk.py book.pdf                       # all chapters -> book_chunks/
  python chunk.py book.pdf --chapters 1-3,7
  python chunk.py book.pdf --include "review questions,key terms"
  python chunk.py book.pdf --list                # show TOC + what would be skipped
  python chunk.py book.pdf --pages-per-chunk 30  # fallback when the PDF has no TOC
"""
import argparse
import re
import sys
from pathlib import Path

import pymupdf
import pymupdf4llm

from clean import MARGINS, RUNNING_HEADER, clean

# Sections excluded by default (matched case-insensitively as substrings of TOC titles).
DEFAULT_EXCLUDE = [
    "review questions", "critical thinking questions", "personal application questions",
    "key terms", "exercises", "answer key", "chapter outline",
    "references", "bibliography", "further reading",
]
# Top-level entries that are not narrative chapters.
FRONT_BACK_MATTER = ["contents", "preface", "index", "appendix", "glossary", "references", "answer key"]


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def matches(title: str, patterns: list[str]) -> bool:
    """Whole-title match, ignoring numbering ("12.5 Key Terms", "Appendix A")."""
    t = re.sub(r"^[\d.\s]+", "", title.lower()).strip()
    return any(t == p or t.startswith(p + " ") for p in patterns)


def parse_ranges(spec: str) -> set[int]:
    out = set()
    for part in spec.split(","):
        a, _, b = part.strip().partition("-")
        out.update(range(int(a), int(b or a) + 1))
    return out


def chapters_from_toc(doc):
    """Return [(title, first_page0, last_page0, [section titles])] for level-1 entries."""
    toc = [e for e in doc.get_toc() if e[2] > 0]
    tops = [i for i, e in enumerate(toc) if e[0] == 1]
    chapters = []
    for n, i in enumerate(tops):
        end_idx = tops[n + 1] if n + 1 < len(tops) else len(toc)
        last = toc[end_idx][2] - 2 if end_idx < len(toc) else doc.page_count - 1
        start = toc[i][2] - 1
        sections = [e[1] for e in toc[i + 1:end_idx]]
        chapters.append((toc[i][1], start, max(start, last), sections))
    return chapters


def drop_sections(text: str, sections: list[str], exclude: list[str]) -> str:
    """Remove each excluded section: from its heading line up to the next TOC heading."""
    keys = {norm(s): matches(s, exclude) for s in sections}
    out, skipping = [], False
    for line in text.splitlines():
        k = norm(line)
        if k in keys:
            skipping = keys[k]
        if not skipping:
            out.append(line)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip() + "\n"


def health_check(doc, pages: list[int]) -> list[str]:
    """Cheap warnings: scanned pages, and repeated short lines (unknown headers/footers)."""
    warnings = []
    sample = pages[::max(1, len(pages) // 40) | 1]  # odd step: mix even/odd pages
    empty = [i + 1 for i in sample if len(doc[i].get_text().strip()) < 50]
    if len(empty) > len(sample) * 0.2:
        warnings.append(f"SCANNED? {len(empty)}/{len(sample)} sampled pages have no text "
                        f"(e.g. p.{empty[:5]}). Run OCR (ocrmypdf) first.")
    # Text in the top/bottom 7% of a page is almost always a running header/footer.
    unknown, pages_with_band = [], 0
    for i in sample:
        h = doc[i].rect.height
        band = [b[4].strip().replace("\n", " ") for b in doc[i].get_text("blocks")
                if b[3] < h * 0.07 or b[1] > h * 0.93]
        pages_with_band += bool(band)
        unknown += [t for t in band if t and not (RUNNING_HEADER.fullmatch(t)
                                                   or t.startswith("Access for free at"))]
    if unknown and pages_with_band > len(sample) * 0.3:
        warnings.append("UNKNOWN HEADER/FOOTER PATTERN (clean.py won't strip it), e.g.: "
                        + " | ".join(list(dict.fromkeys(unknown))[:4]))
    return warnings


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:60]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("--out", help="output dir (default: <pdf>_chunks)")
    ap.add_argument("--chapters", help="1-based chapter numbers among narrative chapters, e.g. 1-3,7")
    ap.add_argument("--include", default="", help="comma list of excluded section names to keep")
    ap.add_argument("--exclude", default="", help="comma list of extra section names to drop")
    ap.add_argument("--list", action="store_true", help="only print the plan")
    ap.add_argument("--pages-per-chunk", type=int, default=30, help="fallback chunk size without TOC")
    a = ap.parse_args()

    include = [s.strip().lower() for s in a.include.split(",") if s.strip()]
    exclude = [p for p in DEFAULT_EXCLUDE if not any(p in i or i in p for i in include)]
    exclude += [s.strip().lower() for s in a.exclude.split(",") if s.strip()]

    pdf = Path(a.pdf)
    doc = pymupdf.open(pdf)
    chapters = chapters_from_toc(doc)
    if chapters:
        chapters = [c for c in chapters if not matches(c[0], FRONT_BACK_MATTER)]
    else:
        print(f"No bookmarks found: falling back to {a.pages_per_chunk}-page chunks.")
        n = a.pages_per_chunk
        chapters = [(f"Part {i // n + 1}", i, min(i + n, doc.page_count) - 1, [])
                    for i in range(0, doc.page_count, n)]

    wanted = parse_ranges(a.chapters) if a.chapters else None
    pages = [p for n, (_, p0, p1, _) in enumerate(chapters, 1)
             if not wanted or n in wanted for p in range(p0, p1 + 1)]
    for w in health_check(doc, pages):
        print("WARNING:", w)
    out_dir = Path(a.out) if a.out else pdf.with_name(pdf.stem + "_chunks")

    for num, (title, p0, p1, sections) in enumerate(chapters, 1):
        if wanted and num not in wanted:
            continue
        skipped = [s for s in sections if matches(s, exclude)]
        if a.list:
            print(f"{num:3d}. {title}  (pp. {p0 + 1}-{p1 + 1})")
            if skipped:
                print("      skip: " + "; ".join(skipped))
            continue
        md = pymupdf4llm.to_markdown(doc, pages=list(range(p0, p1 + 1)),
                                     ignore_images=True, margins=MARGINS)
        text = drop_sections(clean(md), sections, exclude)
        out_dir.mkdir(parents=True, exist_ok=True)
        dst = out_dir / f"{num:02d}_{slug(title)}.txt"
        dst.write_text(text, encoding="utf-8")
        print(f"{dst.name}: {len(text.split())} words, skipped {len(skipped)} section(s)")


if __name__ == "__main__":
    sys.exit(main())
