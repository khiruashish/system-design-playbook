"""Inline markdown -> styled runs (for slides) and -> plain speakable text (for narration)."""
from __future__ import annotations

import re

VISUAL_MAP = {
    "☐": "□",
    "️": "",
    "🔴": "●", "🟢": "●", "⚠": "⚠",
}
EMOJI_RE = re.compile("[\U0001F300-\U0001FAFF☀-⛿✀-➿\u2B00-\u2BFF\uFE0F](?<![✓✗❌✅⚠→])")

INLINE_RE = re.compile(r"(\*\*.+?\*\*|__.+?__|`[^`]+`|(?<![\w*])\*(?!\s)[^*]+?(?<!\s)\*(?!\w)|<br\s*/?>)")


def _pre(s: str) -> str:
    s = re.sub(r":[a-z0-9]+(?:-[a-z0-9]+)+:(\{[^}]*\})?", "", s)  # material icons
    s = re.sub(r"\{\s*[.#][^}]*\}", "", s)  # attr lists
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)  # links
    s = re.sub(r"</?(span|div|mark|kbd|small|strong|em)[^>]*>", "", s)
    s = s.replace("\\|", "|")
    s = re.sub(r"==([^=]+)==", r"\1", s)
    return s


def runs(s: str) -> list[tuple[str, str]]:
    """Split inline markdown into (text, style) runs. style in n,b,i,c; '\n' text = hard break."""
    s = _pre(s)
    for k, v in VISUAL_MAP.items():
        s = s.replace(k, v)
    s = s.replace("❌", "✗").replace("✅", "✓")
    s = EMOJI_RE.sub("", s)
    out: list[tuple[str, str]] = []
    for part in INLINE_RE.split(s):
        if not part:
            continue
        if re.fullmatch(r"<br\s*/?>", part):
            out.append(("\n", "n"))
        elif (part.startswith("**") and part.endswith("**")) or (part.startswith("__") and part.endswith("__")):
            out.extend((t, "b") if t != "\n" else (t, "n") for t in _split_br(part[2:-2]))
        elif part.startswith("`") and part.endswith("`"):
            out.append((part[1:-1], "c"))
        elif part.startswith("*") and part.endswith("*") and len(part) > 2:
            out.append((part[1:-1], "i"))
        else:
            out.append((part, "n"))
    return out


def _split_br(s):
    parts = re.split(r"<br\s*/?>", s)
    res = []
    for k, p in enumerate(parts):
        if k:
            res.append("\n")
        res.append(p)
    return res


def plain(s: str) -> str:
    return "".join(t if t != "\n" else " " for t, _ in runs(s)).strip()


_NUM_WORDS = {"1:1s": "one-on-ones", "1:1": "one-on-one", "1-on-1": "one-on-one"}


def speak(s: str) -> str:
    """Plain text tuned for TTS."""
    t = plain(s)
    for k, v in _NUM_WORDS.items():
        t = t.replace(k, v)
    t = t.replace("✗", "").replace("✓", "").replace("□", "").replace("●", "").replace("⚠", "")
    t = re.sub(r"₹\s?(\d[\d,.]*)\s?(L|lakh|LPA|Cr|crore|k|K)?\b",
               lambda m: m.group(1) + {"L": " lakh", "lakh": " lakh", "LPA": " lakh per annum", "Cr": " crore",
                                       "crore": " crore", "k": " thousand", "K": " thousand"}.get(m.group(2) or "", "")
               + " rupees", t)
    t = re.sub(r"(\d)\s?[–-]\s?(\d)", r"\1 to \2", t)
    t = t.replace("→", ", then ").replace("—", ", ").replace("–", ", ").replace("·", ", ").replace("•", ", ")
    t = t.replace("…", "...").replace("≠", " is not ").replace("≈", " about ").replace("≤", " at most ")
    t = t.replace("−", "minus ").replace("×", " times ").replace("°", " degrees")
    t = re.sub(r"\be\.g\.,?", "for example,", t)
    t = re.sub(r"\bi\.e\.,?", "that is,", t)
    t = re.sub(r"\bvs\.?\b", "versus", t)
    t = re.sub(r"\betc\.", "etcetera.", t)
    t = t.replace("&", " and ")
    t = re.sub(r"(\d)\+", r"\1 plus", t)
    units = {"s": "second", "sec": "second", "ms": "millisecond", "min": "minute", "h": "hour", "hr": "hour",
             "day": "day", "d": "day", "month": "month", "mo": "month", "yr": "year", "year": "year", "user": "user",
             "pod": "pod", "node": "node", "core": "core", "shard": "shard", "GB": "gigabyte", "TB": "terabyte",
             "req": "request", "request": "request", "order": "order", "key": "key", "partition": "partition"}
    t = re.sub(r"(?<=\w)/(" + "|".join(sorted(map(re.escape, units), key=len, reverse=True)) + r")\b",
               lambda m: " per " + units[m.group(1)], t)
    t = re.sub(r"(?<=\w)\s?/\s?(?=\w)", " or ", t)
    t = re.sub(r"[\[\]{}<>#*_`|\"“”]", "", t)
    t = re.sub(r"\s+([,.;:!?])", r"\1", t)
    t = re.sub(r",\s*,+", ",", t)
    t = re.sub(r"([.!?])\s*,", r"\1", t)
    t = re.sub(r"\s{2,}", " ", t).strip(" ,")
    return t


def sentences(t: str) -> list[str]:
    t = t.strip()
    if not t:
        return []
    parts = re.split(r"(?<=[.!?:])\s+(?=[A-Z0-9\"'(])", t)
    out = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        # keep each TTS chunk reasonably short
        while len(p) > 360:
            cut = p.rfind(", ", 0, 300)
            if cut < 80:
                cut = p.rfind(" ", 0, 300)
            out.append(p[:cut + 1].strip())
            p = p[cut + 1:].strip()
        out.append(p)
    return out


def ensure_period(t: str) -> str:
    t = t.strip()
    if t and t[-1] not in ".!?:":
        t += "."
    return t
