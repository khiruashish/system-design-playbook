"""Parse the playbook's MkDocs-Material markdown into a simple block tree.

Only the constructs this playbook actually uses are supported: headings, paragraphs, (nested) lists,
tables, fenced code, admonitions (!!!/???), content tabs (===), and the custom ssp-* <div> widgets
(banner, say, stack, hero) plus Material "grid cards".
"""
from __future__ import annotations

import re

LIST_RE = re.compile(r"^(\s*)([-*+]|\d+\.)\s+(.*)$")
ADMON_RE = re.compile(r'^(!!!|\?\?\?\+?)\s+([\w-]+)(?:\s+"(.*)")?\s*$')
TAB_RE = re.compile(r'^===\+?\s+"(.*)"\s*$')
FENCE_RE = re.compile(r"^(```+|~~~+)")
SNIPPET_ROOT = None  # set by the driver to the repo root for --8<-- includes


def _dedent(lines: list[str], n: int = 4) -> list[str]:
    out = []
    for ln in lines:
        if ln.strip() == "":
            out.append("")
        elif ln.startswith(" " * n):
            out.append(ln[n:])
        else:
            out.append(ln.lstrip())
    return out


def _indented_body(lines: list[str], i: int) -> tuple[list[str], int]:
    """Collect lines indented by >=4 spaces (blank lines allowed) starting at i."""
    body = []
    while i < len(lines):
        ln = lines[i]
        if ln.strip() == "" or ln.startswith("    ") or ln.startswith("\t"):
            body.append(ln)
            i += 1
        else:
            break
    while body and body[-1].strip() == "":
        body.pop()
    return _dedent(body), i


def _div_block(lines: list[str], i: int) -> tuple[list[str], int]:
    """Return inner lines of a <div ...> block starting at lines[i], tracking nesting."""
    depth = 0
    inner = []
    first = True
    while i < len(lines):
        ln = lines[i]
        opens = len(re.findall(r"<div\b", ln))
        closes = len(re.findall(r"</div>", ln))
        if first:
            depth += opens - closes
            first = False
            # content after the opening tag on the same line
            rest = re.sub(r"^\s*<div[^>]*>", "", ln)
            if rest.strip() and depth > 0:
                inner.append(rest)
            i += 1
            if depth <= 0:
                return inner, i
            continue
        depth += opens - closes
        if depth <= 0:
            before = ln.split("</div>")[0]
            if before.strip():
                inner.append(before)
            return inner, i + 1
        inner.append(ln)
        i += 1
    return inner, i


def _strip_common_indent(lines: list[str]) -> list[str]:
    ind = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    n = min(ind) if ind else 0
    return [l[n:] if l.strip() else "" for l in lines]


def parse(text: str) -> list[dict]:
    text = text.replace("\t", "    ")
    if text.startswith("---\n"):
        end = text.find("\n---", 4)
        if end != -1:
            text = text[end + 4:]
    return parse_lines(text.split("\n"))


def parse_lines(lines: list[str]) -> list[dict]:
    blocks: list[dict] = []
    i = 0
    n = len(lines)
    while i < n:
        ln = lines[i]
        s = ln.strip()
        if not s:
            i += 1
            continue

        m = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", ln)
        if m:
            level = len(m.group(1))
            m = re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", re.sub(r'<span class="(band|level)[^"]*">[^<]*</span>', "", ln).replace("⭐", "").rstrip())
            blocks.append({"t": {1: "title", 2: "h2"}.get(level, "h3"), "text": m.group(2)})
            i += 1
            continue

        m = FENCE_RE.match(s)
        if m:
            fence = m.group(1)
            info = s[len(fence):].strip()
            lang = info.split()[0] if info else ""
            tm = re.search(r'title="([^"]*)"', info)
            body = []
            i += 1
            while i < n and not lines[i].strip().startswith(fence):
                body.append(lines[i])
                i += 1
            i += 1
            body = _strip_common_indent(body)
            if lang == "mermaid":
                blocks.append({"t": "mermaid", "text": "\n".join(body).strip()})
                continue
            out = []
            for ln in body:
                sm = re.match(r'^\s*--8<--\s+"([^"]+)"', ln)
                if sm and SNIPPET_ROOT is not None:
                    try:
                        out.extend((SNIPPET_ROOT / sm.group(1)).read_text().rstrip().split("\n"))
                    except OSError:
                        pass
                else:
                    out.append(ln)
            blocks.append({"t": "code", "lang": lang, "title": tm.group(1) if tm else None,
                           "text": "\n".join(out).rstrip()})
            continue

        if s.startswith("$$"):
            body = [s[2:]] if s[2:].strip() else []
            i += 1
            while i < n and not lines[i].strip().endswith("$$"):
                body.append(lines[i].strip())
                i += 1
            if i < n:
                tail = lines[i].strip()[:-2]
                if tail.strip():
                    body.append(tail)
            i += 1
            blocks.append({"t": "math", "text": " ".join(body).strip()})
            continue

        m = ADMON_RE.match(s)
        if m:
            body, i = _indented_body(lines, i + 1)
            blocks.append({"t": "admon", "kind": m.group(2), "title": m.group(3), "blocks": parse_lines(body)})
            continue

        m = TAB_RE.match(s)
        if m:
            body, i = _indented_body(lines, i + 1)
            blocks.append({"t": "tab", "label": m.group(1), "blocks": parse_lines(body)})
            continue

        if s.startswith("<div"):
            cls = re.search(r'class="([^"]*)"', s)
            cls = cls.group(1) if cls else ""
            inner, i = _div_block(lines, i)
            inner = _strip_common_indent(inner)
            if "ssp-banner" in cls:
                blocks.append({"t": "banner", "text": " ".join(l.strip() for l in inner if l.strip())})
            elif "ssp-say" in cls:
                blocks.append({"t": "say", "text": " ".join(l.strip() for l in inner if l.strip())})
            elif "ssp-stack" in cls:
                items = []
                for l in inner:
                    mm = re.match(r"\s*<div>(.*?)(?:<span>\s*(.*?)\s*</span>)?\s*</div>", l)
                    if mm:
                        items.append((mm.group(1).strip(), (mm.group(2) or "").lstrip("—– ").strip()))
                blocks.append({"t": "stack", "items": items})
            elif "grid" in cls and "cards" in cls:
                blocks.append({"t": "cards", "items": _parse_cards(inner)})
            elif "ssp-scene" in cls or "ssp-cube" in cls:
                pass
            else:  # hero / plain wrapper: parse the inner markdown
                blocks.extend(parse_lines(inner))
            continue

        if s.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                rows.append(lines[i].strip())
                i += 1
            cells = [[c.strip() for c in re.split(r"(?<!\\)\|", r.strip("|"))] for r in rows]
            cells = [r for r in cells if not all(re.fullmatch(r":?-{2,}:?", c) for c in r if c)]
            if cells:
                blocks.append({"t": "table", "header": cells[0], "rows": cells[1:]})
            continue

        if LIST_RE.match(ln):
            items = []
            base = len(ln) - len(ln.lstrip())
            while i < n:
                cur = lines[i]
                mm = LIST_RE.match(cur)
                if mm and (len(mm.group(1)) >= base):
                    level = (len(mm.group(1)) - base) // 2
                    items.append({"level": min(level, 2), "ordered": mm.group(2)[0].isdigit(), "text": mm.group(3).strip()})
                    i += 1
                    continue
                if cur.strip() and (cur.startswith(" " * (base + 2))) and items:
                    items[-1]["text"] += " " + cur.strip()
                    i += 1
                    continue
                if not cur.strip() and i + 1 < n and LIST_RE.match(lines[i + 1]) and \
                        len(LIST_RE.match(lines[i + 1]).group(1)) >= base:
                    i += 1
                    continue
                break
            blocks.append({"t": "list", "items": items})
            continue

        if re.fullmatch(r"(-{3,}|\*{3,}|_{3,})", s):  # horizontal rule
            i += 1
            continue

        if s.startswith(">"):  # blockquote -> paragraph
            q = []
            while i < n and lines[i].strip().startswith(">"):
                q.append(lines[i].strip().lstrip(">").strip())
                i += 1
            blocks.append({"t": "para", "text": " ".join(x for x in q if x)})
            continue

        if s.startswith("<"):  # stray html
            i += 1
            continue

        para = []
        while i < n and lines[i].strip() and not (
            re.match(r"^#{1,6}\s", lines[i]) or FENCE_RE.match(lines[i].strip()) or LIST_RE.match(lines[i])
            or lines[i].strip().startswith(("|", "<div", "!!!", "???", "==="))
        ):
            para.append(lines[i].strip())
            i += 1
        txt = " ".join(para)
        # skip pure button/link rows like [Start here](x.md){ .md-button }
        if re.fullmatch(r"(\s*\[[^\]]*\]\([^)]*\)(\{[^}]*\})?\s*)+", txt):
            continue
        blocks.append({"t": "para", "text": txt})
    return blocks


def _parse_cards(inner: list[str]) -> list[tuple[str, str]]:
    cards = []
    cur = None
    for l in inner:
        s = l.strip()
        m = re.match(r"^[-*]\s+(.*)$", s)
        if m and not s.startswith("---"):
            if cur:
                cards.append(cur)
            head = m.group(1)
            head = re.sub(r":[a-z0-9-]+:(\{[^}]*\})?", "", head).strip()
            head = head.strip("_* ")
            cur = [head, []]
            continue
        if cur is None or not s or s == "---":
            continue
        if re.fullmatch(r"\[[^\]]*\]\([^)]*\)", s):
            continue
        cur[1].append(s)
    if cur:
        cards.append(cur)
    return [(h, " ".join(d)) for h, d in cards]
