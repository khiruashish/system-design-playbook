"""Turn a parsed chapter (block tree) into paginated slides, each with its narration."""
from __future__ import annotations

import re
from functools import lru_cache

from PIL import Image

import render as R
from text import ensure_period, runs, sentences, speak

MERMAID = None  # set by the driver to a MermaidRenderer


def latex_to_text(t: str) -> str:
    t = re.sub(r"\\text\{([^}]*)\}", r"\1", t)
    t = re.sub(r"\\frac\{([^}]*)\}\{([^}]*)\}", r"(\1) / (\2)", t)
    for a, b in ((r"\left", ""), (r"\right", ""), (r"\\", " "), (r"\times", "×"), (r"\cdot", "·"),
                 (r"\approx", "≈"), (r"\leq", "≤"), (r"\geq", "≥"), (r"\le", "≤"), (r"\ge", "≥"),
                 (r"\,", " "), (r"\;", " "), (r"\%", "%"), (r"\qquad", "   "), (r"\quad", "   ")):
        t = t.replace(a, b)
    t = re.sub(r"\\([a-zA-Z]+)", r"\1", t)
    t = t.replace("{", "").replace("}", "")
    return re.sub(r"\s+", " ", t).strip()


AVOID_RE = re.compile(r"^\s*(❌|✗)\s*")
DO_RE = re.compile(r"^\s*(✅|✓)\s*")
CHECK_RE = re.compile(r"^\s*(☐|\[ \]|\[x\])\s*", re.I)


@lru_cache(maxsize=None)
def content_top(h2: str, chip: str | None) -> int:
    img = Image.new("RGB", (R.W, 400))
    return max(R.heading(img, h2, chip), R.Y0 - 10)


def _strip_marker(text):
    for rx, kind in ((AVOID_RE, "avoid"), (DO_RE, "do"), (CHECK_RE, "check")):
        if rx.match(text):
            return kind, rx.sub("", text)
    return None, text


class Builder:
    def __init__(self, chapter: str):
        self.chapter = chapter
        self.slides: list[dict] = []
        self.h2 = chapter
        self.h3 = None
        self.tab = None
        self.prefix: list[str] = []
        self.pending: list[dict] = []
        self.part = 0

    # ---- context
    @property
    def chip(self):
        parts = [p for p in (self.h3, self.tab) if p]
        return "  ·  ".join(R_plain(p) for p in parts) or None

    def top(self):
        return content_top(self.h2, self.chip)

    def emit(self, slide: dict, narration: list[str]):
        slide.setdefault("h2", self.h2)
        slide.setdefault("chip", self.chip)
        text = " ".join(ensure_period(speak(p)) if not p.startswith("\x00") else p[1:] for p in self.prefix)
        body = " ".join(ensure_period(n) for n in narration if n and n.strip())
        slide["narration"] = (text + " " + body).strip()
        self.prefix = []
        self.slides.append(slide)

    # ---- text items
    def add_item(self, item):
        self.pending.append(item)

    def flush(self):
        items, self.pending = self.pending, []
        if not items:
            return
        avail = R.Y1 - self.top() - 112
        page: list[dict] = []
        pages = []
        for it in items:
            if page and R.text_block_height(page + [it], 40) > avail:
                pages.append(page)
                page = []
            if not page and R.text_block_height([it], 32) > avail:
                # split very long item into sentence chunks
                chunks = _split_runs(it["runs"])
                for ch in chunks:
                    sub = dict(it, runs=ch, speech=speak("".join(t for t, _ in ch)))
                    if page and R.text_block_height(page + [sub], 36) > avail:
                        pages.append(page)
                        page = []
                    page.append(sub)
                continue
            page.append(it)
        if page:
            pages.append(page)
        for pg in pages:
            size = next((s for s in (46, 44, 42, 40, 38, 36, 34, 32) if R.text_block_height(pg, s) <= avail), 30)
            self.emit({"kind": "text", "items": pg, "size": size}, [it["speech"] for it in pg])

    # ---- blocks
    def block(self, b):
        t = b["t"]
        if t == "title":
            return
        if t == "h2":
            self.flush()
            self.h2, self.h3, self.tab = R_plain(b["text"]), None, None
            self.part += 1
            nm = re.match(r"^\s*(\d+)\.\s*(.*)$", R_plain(b["text"]))
            label, spoken = (nm.group(1), nm.group(2)) if nm else (str(self.part), R_plain(b["text"]))
            self.emit({"kind": "section", "part": label}, [f"Part {label}. {speak(spoken)}"])
            return
        if t == "h3":
            self.flush()
            self.h3, self.tab = R_plain(b["text"]), None
            self.prefix.append(re.sub(r"^\s*\d+(\.\d+)*\.?\s+", "", R_plain(b["text"])))
            return
        if t == "para":
            self.add_item({"kind": "para", "runs": runs(b["text"]), "speech": speak(b["text"])})
            return
        if t == "list":
            for k, it in enumerate(b["items"]):
                kind, txt = _strip_marker(it["text"])
                kind = kind or ("num" if it["ordered"] else "bullet")
                pre = {"avoid": "Avoid: ", "do": "Do: "}.get(kind, "")
                self.add_item({"kind": kind, "level": it["level"], "runs": runs(txt), "speech": pre + speak(txt),
                               "n": None})
            # number ordered items sequentially per level-0 run
            n = 0
            for item in self.pending:
                if item["kind"] == "num" and item.get("n") is None:
                    n += 1
                    item["n"] = n
            return
        self.flush()
        getattr(self, "b_" + t)(b)

    def b_tab(self, b):
        prev = self.tab
        self.tab = R_plain(b["label"])
        self.prefix.append(b["label"])
        for c in b["blocks"]:
            self.block(c)
        self.flush()
        self.tab = prev

    def b_banner(self, b):
        r = runs(b["text"])
        avail = R.Y1 - self.top() - 20
        size = next((s for s in (52, 48, 44, 40, 36) if R.banner_height(r, s) <= avail), 34)
        lead = "The key idea." if not any(s["kind"] == "banner" for s in self.slides) else ""
        self.emit({"kind": "banner", "runs": r, "size": size}, [lead, speak(b["text"])])

    def b_say(self, b):
        r = runs(b["text"])
        avail = R.Y1 - self.top()
        size = next((s for s in (46, 44, 42, 40, 38, 36, 34) if R.say_height(r, s) <= avail), None)
        chunks = [r] if size else _split_runs(r, max_chars=420)
        for k, ch in enumerate(chunks):
            sz = size or next((s for s in (40, 38, 36, 34, 32) if R.say_height(ch, s) <= avail), 30)
            label = f"{k + 1} of {len(chunks)}" if len(chunks) > 1 else None
            spoken = speak("".join(t for t, _ in ch))
            self.emit({"kind": "say", "runs": ch, "size": sz, "part_label": label},
                      (["You could say:"] if k == 0 else []) + [spoken])

    def b_stack(self, b):
        items = [(runs(h), runs(d), None) for h, d in b["items"]]
        for i in range(0, len(items), 6):
            chunk = items[i:i + 6]
            narr = []
            for k, (h, d, _) in enumerate(chunk):
                hs = speak("".join(t for t, _ in h))
                ds = speak("".join(t for t, _ in d))
                hs = re.sub(r"^(\d+)\.\s*", r"Step \1: ", hs)
                narr.append(f"{hs}{': ' + ds if ds else ''}")
            fixed = []
            for h, d, _ in chunk:
                ht = "".join(t for t, _ in h)
                m = re.match(r"^(\d+)\.\s*(.*)$", ht)
                if m:
                    fixed.append(([(m.group(2), "n")], d, int(m.group(1))))
                else:
                    fixed.append((h, d, None))
            if all(n is None for _, _, n in fixed):
                fixed = [(h, d, i + k + 1) for k, (h, d, _) in enumerate(fixed)]
            self.emit({"kind": "stack", "items": fixed}, narr)

    def b_cards(self, b):
        items = [(runs(t), runs(d)) for t, d in b["items"]]
        for i in range(0, len(items), 4):
            chunk = items[i:i + 4]
            narr = [f"{speak(''.join(t for t, _ in t_))}: {speak(''.join(t for t, _ in d))}" for t_, d in chunk]
            self.emit({"kind": "cards", "items": chunk}, narr)

    def b_table(self, b):
        header = [runs(c) for c in b["header"]]
        rows = [[runs(c) for c in r] for r in b["rows"]]
        hs = [speak(c) for c in b["header"]]
        avail = R.Y1 - self.top()
        pages, page = [], []
        for r in rows:
            if page and R.table_height(header, page + [r], 30) > avail:
                pages.append(page)
                page = []
            page.append(r)
        if page:
            pages.append(page)
        for pi, pg in enumerate(pages):
            size = next((s for s in (36, 34, 32, 30) if R.table_height(header, pg, s) <= avail), 28)
            narr = []
            if pi == 0:
                narr.append("Here is a quick table: " + ", and ".join(h for h in hs if h) + "." if any(hs) else "")
            for r in pg:
                cells = [speak("".join(t for t, _ in c)) for c in r]
                if len(cells) == 2 and hs[0]:
                    narr.append(f"{cells[0]}: {cells[1]}")
                else:
                    first = cells[0]
                    rest = [f"{hs[c] + ': ' if c < len(hs) and hs[c] else ''}{cells[c]}"
                            for c in range(1, len(cells)) if cells[c]]
                    narr.append(ensure_period(first) + " " + " ".join(ensure_period(x) for x in rest))
            self.emit({"kind": "table", "header": header, "rows": pg, "size": size}, narr)

    def b_code(self, b):
        src = b["text"].split("\n")
        skipped = 0
        if (b.get("lang") or "") in ("java", "kotlin"):  # package/import boilerplate is not worth screen space
            k = 0
            while k < len(src) and (not src[k].strip() or re.match(r"\s*(package|import)\s", src[k])):
                k += 1
            skipped = sum(1 for x in src[:k] if x.strip())
            src = src[k:]
        if skipped:
            src = [f"// … {skipped} package/import lines omitted"] + src
        lines = R.code_wrap("\n".join(src))
        if not lines or not b["text"].strip():
            return
        step = int(R.CODE_SIZE * 1.5)
        per = max(4, (R.Y1 - self.top() - 130) // step)
        shown = lines[:per]
        more = len(lines) - len(shown)
        lang = {"java": "Java", "kotlin": "Kotlin", "sql": "SQL", "yaml": "YAML", "bash": "shell", "json": "JSON",
                "http": "HTTP", "text": "", "": ""}.get(b.get("lang") or "", (b.get("lang") or "").upper())
        label = " · ".join(x for x in (lang or "TEXT", b.get("title")) if x)
        if more:
            label += f" · first {len(shown)} of {len(lines)} lines"
        if lang:
            intro = f"Here is the {lang} code" + (f", {b['title']}" if b.get("title") else "") + "."
            if more:
                intro += f" It runs to {len(lines)} lines; the full listing is in the chapter."
            narr = [intro]
            # read out leading comments: they explain what the code does
            for ln in src[1 if skipped else 0:per]:
                cm = re.match(r"\s*(?://|#|--|\*|/\*\*?)\s*(.{12,})", ln)
                if cm and len(narr) < 4 and not cm.group(1).startswith(("@", "-", "=")):
                    narr.append(speak(cm.group(1).rstrip("*/ ")))
        else:  # text blocks are usually outputs, layouts or worked numbers: read them if short
            narr = ["On screen:"] + [speak(x) for x in shown[:14] if speak(x)]
            if more or len(shown) > 14:
                narr.append("The rest is in the chapter.")
        self.emit({"kind": "code", "lines": shown, "label": label}, narr)

    def b_mermaid(self, b):
        png = MERMAID.render(b["text"]) if MERMAID else None
        if not png:
            return
        narr = ["Here is the diagram."]
        if re.match(r"\s*sequenceDiagram", b["text"]):
            steps = []
            for ln in b["text"].split("\n"):
                m = re.match(r"\s*([\w .()/-]+?)\s*-[-)x>]+[+-]?\s*([\w .()/-]+?)\s*:\s*(.+)$", ln)
                if m:
                    steps.append(f"{m.group(1)} to {m.group(2)}: {speak(m.group(3))}")
            if steps:
                narr = ["The sequence diagram walks through it step by step."] + steps[:14]
                if len(steps) > 14:
                    narr.append("and so on, as shown on screen.")
        self.emit({"kind": "diagram", "png": str(png)}, narr)

    def b_math(self, b):
        t = latex_to_text(b["text"])
        self.emit({"kind": "callout", "admon": "info", "title": "Formula",
                   "items": [{"kind": "para", "runs": [(t, "c")], "speech": t}], "size": 40},
                  ["The formula:", speak(t.replace("_", " "))])

    def b_qa(self, b):
        title = R_plain((b.get("title") or "").replace("⭐", ""))
        m = re.match(r"^Q(\d+)\s*(?:\[(B\d)\])?\s*(.*)$", title)
        num, band, q = (m.group(1), m.group(2), m.group(3)) if m else (None, None, title)
        answer = None
        for c in b["blocks"]:
            if c["t"] == "para":
                txt = c["text"]
                answer = re.sub(r"^\*\*30-second answer:\*\*\s*", "", txt) if answer is None else answer
                if "30-second answer" in txt:
                    answer = re.sub(r"^\*\*30-second answer:\*\*\s*", "", txt)
                    break
        r = runs(answer or "")
        avail = R.Y1 - self.top()
        size = next((s for s in (40, 38, 36, 34, 32, 30) if R.qa_height(runs(q), r, s) <= avail), 28)
        lead = f"Question {num}." if num else "Question."
        self.emit({"kind": "qa", "q": runs(q), "a": r, "num": num, "band": band, "size": size},
                  [lead, speak(q), "Thirty-second answer:" if answer else "", speak(answer or "")])

    def b_admon(self, b):
        if b["kind"] == "question":
            return self.b_qa(b)
        col, default = R.ADMON.get(b["kind"], R.ADMON["note"])
        title = R_plain(b["title"]) if b.get("title") else default
        items = []
        for c in b["blocks"]:
            items.extend(_items_from(c))
        if not items:
            return
        avail = R.Y1 - self.top() - 200
        pages, page = [], []
        for it in items:
            if page and R.text_block_height(page + [it], 36) > avail:
                pages.append(page)
                page = []
            page.append(it)
        pages.append(page)
        for k, pg in enumerate(pages):
            size = next((s for s in (42, 40, 38, 36, 34, 32) if R.text_block_height(pg, s) <= avail), 30)
            head = f"{title}." if k == 0 else ""
            if k == 0 and b.get("title") and title.lower() != default.lower():
                head = f"{default}: {speak(b['title'])}."
            self.emit({"kind": "callout", "admon": b["kind"], "title": title, "items": pg, "size": size},
                      [head] + [it["speech"] for it in pg])


def _items_from(c):
    t = c["t"]
    if t == "para":
        return [{"kind": "para", "runs": runs(c["text"]), "speech": speak(c["text"])}]
    if t == "say":
        r = runs(c["text"])
        return [{"kind": "para", "runs": [(x, "i" if s == "n" else s) for x, s in r], "speech": speak(c["text"])}]
    if t == "list":
        out = []
        n = 0
        for it in c["items"]:
            kind, txt = _strip_marker(it["text"])
            kind = kind or ("num" if it["ordered"] else "bullet")
            if kind == "num":
                n += 1
            out.append({"kind": kind, "level": it["level"], "runs": runs(txt), "n": n,
                        "speech": {"avoid": "Avoid: ", "do": "Do: "}.get(kind, "") + speak(txt)})
        return out
    if t == "code":
        return [{"kind": "para", "runs": [(ln, "c")], "speech": speak(ln)} for ln in c["text"].split("\n") if ln.strip()]
    if t == "table":
        return [{"kind": "bullet", "level": 0, "runs": runs(" — ".join(r)), "speech": speak(", ".join(r))}
                for r in c["rows"]]
    if t in ("tab", "admon"):
        out = []
        for x in c["blocks"]:
            out.extend(_items_from(x))
        return out
    if t in ("h2", "h3"):
        return [{"kind": "para", "runs": [(c["text"], "b")], "speech": speak(c["text"])}]
    return []


def _split_runs(r, max_chars=300):
    """Split runs into chunks at sentence boundaries."""
    chunks, cur, n = [], [], 0
    for text, style in r:
        pieces = re.split(r"(?<=[.!?])\s+", text) if style == "n" else [text]
        for k, p in enumerate(pieces):
            if not p:
                continue
            piece = p + (" " if k < len(pieces) - 1 else "")
            cur.append((piece, style))
            n += len(piece)
            if n >= max_chars and re.search(r"[.!?]\s*$", piece):
                chunks.append(cur)
                cur, n = [], 0
    if cur:
        chunks.append(cur)
    return chunks or [r]


def R_plain(s):
    from text import plain
    return plain(s)


def build(blocks: list[dict], meta: dict) -> list[dict]:
    title = next((b["text"] for b in blocks if b["t"] == "title"), meta["chapter"])
    title = re.sub(r"^[A-Z]?\d+[a-z]?\.\s+", "", R_plain(title)).strip()
    b = Builder(R_plain(title))
    where = f"Chapter {meta['number']}, from the {meta['section']} part of the System Design Playbook." if meta.get("number") else f"From the {meta['section']} part of the System Design Playbook." if meta["section"] else \
        "From the System Design Playbook."
    b.slides.append({"kind": "title", "title": R_plain(title), "h2": "", "chip": None,
                     "narration": f"{ensure_period(speak(title))} {where}"})
    for blk in blocks:
        b.block(blk)
    b.flush()
    b.slides.append({"kind": "end", "title": R_plain(title), "h2": "", "chip": None,
                     "narration": "That's the end of this chapter. Try the exercises and the hands-on labs, then answer "
                                  "the interview questions out loud without looking. See you in the next chapter."})
    return b.slides
