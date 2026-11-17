"""Slide rendering with Pillow (1920x1080). Also exposes measure helpers used for pagination."""
from __future__ import annotations

from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
X0, X1 = 150, 1770
Y0, Y1 = 285, 975
CW = X1 - X0

PURPLE = (79, 70, 229)
VIOLET = (147, 51, 234)
ORANGE = (8, 145, 178)
INK = (31, 22, 54)
MUTED = (96, 88, 120)
BG = (247, 248, 253)
CARD = (255, 255, 255)
LINE = (226, 220, 240)

ADMON = {
    "tip": ((0, 137, 123), "Tip"), "success": ((67, 160, 71), "Success"),
    "note": ((30, 136, 229), "Note"), "info": ((30, 136, 229), "Info"),
    "warning": ((239, 108, 0), "Warning"), "danger": ((211, 47, 47), "Watch out"),
    "example": ((126, 87, 194), "Example"), "quote": ((97, 97, 97), "Quote"),
    "abstract": ((8, 145, 178), "At a glance"), "bug": ((211, 47, 47), "Bug"), "question": ((79, 70, 229), "Question"),
}

FD = "/usr/share/fonts/opentype/inter/"
FONT_FILES = {
    "n": FD + "Inter-Regular.otf", "b": FD + "Inter-Bold.otf", "i": FD + "Inter-Italic.otf",
    "bi": FD + "Inter-BoldItalic.otf", "s": FD + "Inter-SemiBold.otf", "m": FD + "Inter-Medium.otf",
    "d": FD + "InterDisplay-ExtraBold.otf", "db": FD + "InterDisplay-Bold.otf",
    "c": "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-Regular.ttf",
    "cb": "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-Bold.ttf",
    "sym": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
}


@lru_cache(maxsize=None)
def font(style: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_FILES[style], size)


# ---------------------------------------------------------------- rich-text layout

def _style_font(style, size, base="n"):
    if style == "c":
        return font("c", int(size * 0.9))
    if base == "i":
        return font("bi" if style == "b" else "i", size)
    if base == "b" or base == "s":
        return font("b" if style == "b" else base, size)
    return font({"n": "n", "b": "b", "i": "i"}.get(style, "n"), size)


def wrap(runs, size, maxw, base="n"):
    """runs -> list of lines; each line = list of (text, style, x_offset). Returns lines."""
    tokens = []  # (word, style, space_before)
    pending_space = False
    for text, style in runs:
        if text == "\n":
            tokens.append(("\n", style, False))
            pending_space = False
            continue
        parts = text.replace("\n", " ").split(" ")
        for k, p in enumerate(parts):
            if p == "":
                pending_space = True
                continue
            tokens.append((p, style, pending_space or k > 0))
            pending_space = False
    lines = [[]]
    x = 0.0
    for word, style, space in tokens:
        if word == "\n":
            lines.append([])
            x = 0.0
            continue
        f = _style_font(style, size, base)
        sp = (f if style == "c" else font("n", size)).getlength(" ") if (space and lines[-1]) else 0.0
        wlen = f.getlength(word)
        if lines[-1] and x + sp + wlen > maxw:
            lines.append([])
            x = 0.0
            sp = 0.0
        # merge adjacent words with same style to reduce draw calls
        if lines[-1] and lines[-1][-1][1] == style and sp:
            t, s, ox = lines[-1][-1]
            lines[-1][-1] = (t + " " + word, s, ox)
        else:
            lines[-1].append((word if not sp else word, style, x + sp))
        x += sp + wlen
    if lines and not lines[-1] and len(lines) > 1:
        lines.pop()
    return lines


def text_height(runs, size, maxw, lh=1.38, base="n"):
    return len(wrap(runs, size, maxw, base)) * int(size * lh)


def draw_lines(d, lines, x, y, size, color, lh=1.38, base="n", bold_color=None, code_bg=(238, 234, 248)):
    step = int(size * lh)
    for line in lines:
        for text, style, ox in line:
            f = _style_font(style, size, base)
            col = bold_color if (style == "b" and bold_color) else color
            if style == "c":
                w = f.getlength(text)
                d.rounded_rectangle((x + ox - 6, y + size * 0.08, x + ox + w + 6, y + size * 1.18), 8, fill=code_bg)
                d.text((x + ox, y + size * 0.16), text, font=f, fill=(90, 40, 140))
            else:
                d.text((x + ox, y), text, font=f, fill=col)
        y += step
    return y


# ---------------------------------------------------------------- backgrounds

@lru_cache(maxsize=4)
def _gradient(w, h, diag=True):
    xs = np.linspace(0, 1, w)[None, :]
    ys = np.linspace(0, 1, h)[:, None]
    t = (xs * 0.75 + ys * 0.25) if diag else np.repeat(xs, h, 0)
    t = np.clip(t, 0, 1)
    stops = [(0.0, PURPLE), (0.48, VIOLET), (1.0, ORANGE)]
    img = np.zeros((h, w, 3))
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        m = (t >= a) & (t <= b)
        k = ((t - a) / (b - a))
        for c in range(3):
            img[..., c] = np.where(m, ca[c] + (cb[c] - ca[c]) * k, img[..., c])
    return Image.fromarray(img.astype(np.uint8))


def _shadow(base, box, radius=28, blur=22, alpha=60, offset=(0, 14)):
    x0, y0, x1, y1 = box
    pad = blur * 3
    sh = Image.new("L", (int(x1 - x0) + pad * 2, int(y1 - y0) + pad * 2), 0)
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad, pad + x1 - x0, pad + y1 - y0), radius, fill=alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    col = Image.new("RGB", sh.size, (40, 20, 80))
    base.paste(col, (int(x0 - pad + offset[0]), int(y0 - pad + offset[1])), sh)


def card(img, box, fill=CARD, radius=28, shadow=True, outline=None):
    if shadow:
        _shadow(img, box, radius)
    ImageDraw.Draw(img).rounded_rectangle(box, radius, fill=fill, outline=outline, width=2 if outline else 0)


def chrome(meta, idx, total):
    """Standard content-slide frame: header bar, heading, chip, footer progress."""
    img = Image.new("RGB", (W, H), BG)
    # soft decorative blobs
    deco = Image.new("L", (W, H), 0)
    dd = ImageDraw.Draw(deco)
    dd.ellipse((1500, -260, 2200, 440), fill=38)
    dd.ellipse((-300, 760, 380, 1400), fill=26)
    deco = deco.filter(ImageFilter.GaussianBlur(60))
    img.paste(Image.new("RGB", (W, H), (205, 190, 245)), (0, 0), deco)

    bar = _gradient(W, 84)
    img.paste(bar, (0, 0))
    d = ImageDraw.Draw(img)
    d.text((X0, 24), "SYSTEM DESIGN PLAYBOOK", font=font("b", 26), fill=(255, 255, 255))
    lx = X0 + font("b", 26).getlength("SYSTEM DESIGN PLAYBOOK") + 22
    crumb = f"{meta['section']}  ›  {meta['chapter']}" if meta["section"] else meta["chapter"]
    d.text((lx, 26), crumb, font=font("m", 24), fill=(255, 255, 255, 230))
    num = f"Ch {meta['number']}" if meta.get("number") else ""
    if num:
        d.text((X1 - font("s", 24).getlength(num), 26), num, font=font("s", 24), fill=(255, 255, 255))

    # footer progress
    d.rounded_rectangle((X0, 1028, X1, 1036), 4, fill=LINE)
    frac = (idx + 1) / max(total, 1)
    if frac > 0:
        prog = _gradient(int(CW * frac) or 1, 8, diag=False)
        m = Image.new("L", prog.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, prog.size[0] - 1, 7), 4, fill=255)
        img.paste(prog, (X0, 1028), m)
    d.text((X1 - 140, 990), f"{idx + 1} / {total}", font=font("m", 24), fill=MUTED, anchor="la")
    return img


def heading(img, h2, chip):
    d = ImageDraw.Draw(img)
    y = 128
    size = 58
    while font("d", size).getlength(h2) > CW and size > 40:
        size -= 2
    lines = wrap([(h2, "n")], size, CW, base="d") if font("d", size).getlength(h2) > CW else [[(h2, "n", 0)]]
    for line in lines[:2]:
        d.text((X0, y), " ".join(t for t, _, _ in line), font=font("d", size), fill=INK)
        y += int(size * 1.12)
    y += 14
    d.rounded_rectangle((X0, y + 8, X0 + 90, y + 16), 4, fill=ORANGE)
    if chip:
        f = font("s", 28)
        cw = f.getlength(chip)
        if cw > CW - 300:
            while f.getlength(chip + "…") > CW - 300:
                chip = chip[:-1]
            chip += "…"
            cw = f.getlength(chip)
        cx = X0 + 110
        d.rounded_rectangle((cx, y - 6, cx + cw + 44, y + 36), 21, fill=(237, 231, 252))
        d.text((cx + 22, y - 1), chip, font=f, fill=PURPLE)
    return y + 60


# ---------------------------------------------------------------- slide kinds

def render(slide, meta, idx, total) -> Image.Image:
    k = slide["kind"]
    if k == "title":
        return _title(slide, meta)
    if k == "section":
        return _section(slide, meta, idx, total)
    if k == "end":
        return _end(slide, meta)
    img = chrome(meta, idx, total)
    top = heading(img, slide["h2"], slide.get("chip"))
    top = max(top, Y0 - 10)
    fn = {"text": _text, "say": _say, "banner": _banner, "stack": _stack, "cards": _cards,
          "table": _table, "code": _code, "callout": _callout, "diagram": _diagram, "qa": _qa}[k]
    fn(img, slide, top)
    return img


def _title(slide, meta):
    img = _gradient(W, H).copy()
    d = ImageDraw.Draw(img)
    # decorative translucent rings
    ov = Image.new("L", (W, H), 0)
    od = ImageDraw.Draw(ov)
    for r, a in ((520, 40), (380, 34), (240, 30)):
        od.ellipse((1560 - r, 540 - r, 1560 + r, 540 + r), outline=a, width=26)
    img.paste(Image.new("RGB", (W, H), (255, 255, 255)), (0, 0), ov)
    d.text((X0, 150), "SYSTEM DESIGN PLAYBOOK", font=font("b", 34), fill=(255, 255, 255))
    if meta["section"]:
        tag = meta["section"].upper() + (f"  ·  CHAPTER {meta['number']}" if meta.get("number") else "")
        f = font("b", 26)
        d.rounded_rectangle((X0, 230, X0 + f.getlength(tag) + 48, 282), 26, fill=(255, 255, 255))
        d.text((X0 + 24, 241), tag, font=f, fill=PURPLE)
    size = 104
    title = slide["title"]
    while size > 64 and len(wrap([(title, "n")], size, 1180, base="d")) > 3:
        size -= 6
    lines = wrap([(title, "n")], size, 1180, base="d")
    y = 340
    for line in lines:
        d.text((X0, y), " ".join(t for t, _, _ in line), font=font("d", size), fill=(255, 255, 255))
        y += int(size * 1.08)
    if slide.get("subtitle"):
        sub = wrap(slide["subtitle"], 36, 1150, base="m")[:4]
        draw_lines(d, sub, X0, y + 30, 36, (255, 255, 255), base="m")
    d.text((X0, 960), "Narrated chapter walkthrough  ·  concepts, diagrams, code and interview Q&A",
           font=font("m", 26), fill=(255, 255, 255))
    return img


def _section(slide, meta, idx, total):
    img = chrome(meta, idx, total)
    d = ImageDraw.Draw(img)
    box = (X0, 300, X1, 780)
    card(img, box, radius=40)
    g = _gradient(26, 480)
    m = Image.new("L", g.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 25, 479), 12, fill=255)
    img.paste(g, (X0, 300), m)
    d.text((X0 + 90, 370), f"PART {slide['part']}", font=font("b", 30), fill=ORANGE)
    size = 84
    while len(wrap([(slide["h2"], "n")], size, CW - 180, base="d")) > 3 and size > 50:
        size -= 6
    lines = wrap([(slide["h2"], "n")], size, CW - 180, base="d")
    y = 430
    for line in lines:
        d.text((X0 + 90, y), " ".join(t for t, _, _ in line), font=font("d", size), fill=INK)
        y += int(size * 1.1)
    return img


# --- text (paragraph + bullet list items)

def item_metrics(item, size):
    indent = 0 if item["kind"] == "para" else 62 + item.get("level", 0) * 54
    return indent, text_height(item["runs"], size, CW - 112 - indent)


def text_block_height(items, size):
    gap = int(size * 0.55)
    return sum(item_metrics(it, size)[1] for it in items) + gap * max(len(items) - 1, 0)


def _draw_items(img, items, x, y, w, size, color=INK):
    d = ImageDraw.Draw(img)
    gap = int(size * 0.55)
    num = 0
    for it in items:
        indent = 0 if it["kind"] == "para" else 62 + it.get("level", 0) * 54
        lines = wrap(it["runs"], size, w - indent)
        cy = y + size * 0.72
        bx = x + indent - 40
        if it["kind"] == "bullet":
            r = 9 if it.get("level", 0) == 0 else 7
            d.ellipse((bx - r, cy - r, bx + r, cy + r), fill=ORANGE if it.get("level", 0) == 0 else VIOLET)
        elif it["kind"] == "num":
            num = it.get("n", num + 1)
            d.ellipse((bx - 22, cy - 22, bx + 22, cy + 22), fill=PURPLE)
            d.text((bx, cy), str(num), font=font("b", 26), fill=(255, 255, 255), anchor="mm")
        elif it["kind"] in ("avoid", "do", "check"):
            col = {"avoid": (211, 47, 47), "do": (46, 125, 50), "check": PURPLE}[it["kind"]]
            sym = {"avoid": "✗", "do": "✓", "check": "☐"}[it["kind"]]
            d.ellipse((bx - 22, cy - 22, bx + 22, cy + 22), fill=col)
            d.text((bx, cy + 1), sym, font=font("sym", 28), fill=(255, 255, 255), anchor="mm")
        y = draw_lines(d, lines, x + indent, y, size, color, bold_color=PURPLE)
        y += gap
    return y


def _text(img, s, top):
    size = s["size"]
    h = text_block_height(s["items"], size)
    pad = 56
    box = (X0, top, X1, min(top + h + pad * 2, Y1))
    card(img, box)
    _draw_items(img, s["items"], X0 + pad, top + pad, CW - pad * 2, size)


# --- say (script)

def _say(img, s, top):
    size = s["size"]
    pad = 60
    lines = wrap(s["runs"], size, CW - pad * 2 - 40, base="n")
    h = len(lines) * int(size * 1.42)
    label_h = 64
    box = (X0, top, X1, min(top + h + pad * 2 + label_h, Y1))
    card(img, box)
    d = ImageDraw.Draw(img)
    g = _gradient(16, int(box[3] - box[1]))
    m = Image.new("L", g.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 15, g.size[1] - 1), 8, fill=255)
    img.paste(g, (X0, top), m)
    d.text((X0 + pad + 20, top + 34), "SAY IT LIKE THIS" + (f"  ·  {s['part_label']}" if s.get("part_label") else ""),
           font=font("b", 26), fill=ORANGE)
    d.text((X1 - 150, top - 40), "“", font=font("d", 220), fill=(225, 214, 250))
    draw_lines(d, lines, X0 + pad + 20, top + pad + label_h - 10, size, INK, lh=1.42, bold_color=PURPLE)


def say_height(runs, size):
    return len(wrap(runs, size, CW - 160)) * int(size * 1.42) + 184


# --- banner (key idea)

def _banner(img, s, top):
    size = s["size"]
    pad = 70
    lines = wrap(s["runs"], size, CW - pad * 2, base="m")
    h = len(lines) * int(size * 1.4)
    box = (X0, top + 20, X1, top + 20 + h + pad * 2 + 50)
    _shadow(img, box, 36)
    g = _gradient(int(box[2] - box[0]), int(box[3] - box[1]))
    m = Image.new("L", g.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, g.size[0] - 1, g.size[1] - 1), 36, fill=255)
    img.paste(g, (int(box[0]), int(box[1])), m)
    d = ImageDraw.Draw(img)
    d.text((X0 + pad, top + 20 + 44), "KEY IDEA", font=font("b", 26), fill=(255, 236, 200))
    draw_lines(d, lines, X0 + pad, top + 20 + pad + 40, size, (255, 255, 255), lh=1.4, base="m",
               bold_color=(255, 255, 255))


def banner_height(runs, size):
    return len(wrap(runs, size, CW - 140, base="m")) * int(size * 1.4) + 210


# --- stack (numbered steps)

def _stack(img, s, top):
    d = ImageDraw.Draw(img)
    items = s["items"]
    avail = Y1 - top
    gap = 18
    rowh = min(150, (avail - gap * (len(items) - 1)) // max(len(items), 1))
    hs = 36 if rowh >= 110 else 30
    ds = 30 if rowh >= 110 else 26
    y = top
    for k, (head, desc, n) in enumerate(items):
        box = (X0, y, X1, y + rowh)
        card(img, box, radius=24, shadow=True)
        cx, cy = X0 + 70, y + rowh // 2
        g = _gradient(84, 84)
        m = Image.new("L", (84, 84), 0)
        ImageDraw.Draw(m).ellipse((0, 0, 83, 83), fill=255)
        img.paste(g, (cx - 42, cy - 42), m)
        d.text((cx, cy), str(n) if n else str(k + 1), font=font("b", 38), fill=(255, 255, 255), anchor="mm")
        tx = X0 + 150
        hl = wrap(head, hs, CW - 200, base="b")
        dl = wrap(desc, ds, CW - 200) if desc else []
        th = len(hl) * int(hs * 1.25) + len(dl) * int(ds * 1.3)
        ty = cy - th // 2
        ty = draw_lines(d, hl, tx, ty, hs, INK, lh=1.25, base="b")
        draw_lines(d, dl, tx, ty, ds, MUTED, lh=1.3)
        y += rowh + gap


# --- cards grid

def _cards(img, s, top):
    d = ImageDraw.Draw(img)
    items = s["items"]
    cols = 2 if len(items) > 1 else 1
    rows = (len(items) + cols - 1) // cols
    gap = 30
    cw = (CW - gap * (cols - 1)) // cols
    ch = min(330, (Y1 - top - gap * (rows - 1)) // rows)
    ts, ds = (36, 29) if ch > 220 else (32, 26)
    for k, (title, desc) in enumerate(items):
        r, c = divmod(k, cols)
        x = X0 + c * (cw + gap)
        y = top + r * (ch + gap)
        card(img, (x, y, x + cw, y + ch), radius=26)
        d.rounded_rectangle((x + 40, y + 40, x + 96, y + 48), 4, fill=ORANGE)
        ty = draw_lines(d, wrap(title, ts, cw - 80, base="b"), x + 40, y + 66, ts, PURPLE, lh=1.2, base="b")
        dl = wrap(desc, ds, cw - 80)
        maxl = max(1, int((y + ch - 30 - ty - 10) // int(ds * 1.35)))
        draw_lines(d, dl[:maxl], x + 40, ty + 10, ds, MUTED, lh=1.35)


# --- table

def table_layout(header, rows, size):
    ncol = len(header)
    lens = []
    for c in range(ncol):
        col = [header[c]] + [r[c] if c < len(r) else [] for r in rows]
        lens.append(max(8, min(60, max(sum(len(t) for t, _ in cell) for cell in col))))
    tot = sum(lens)
    pad = 28
    # a column is never narrower than its longest unbreakable word
    minw = []
    for c in range(ncol):
        col = [header[c]] + [r[c] if c < len(r) else [] for r in rows]
        longest = max((font("b", size).getlength(w) for cell in col for t, _ in cell for w in t.split()), default=0)
        minw.append(min(longest + pad * 2 + 4, CW / 2))
    widths = [max(220, minw[c], CW * l / tot) for c, l in enumerate(lens)]
    scale = CW / sum(widths)
    widths = [w * scale for w in widths]

    def row_h(cells, bold=False):
        hmax = 0
        for c in range(ncol):
            cell = cells[c] if c < len(cells) else []
            hmax = max(hmax, len(wrap(cell, size, widths[c] - pad * 2, base="b" if (bold or c == 0) else "n")) * int(size * 1.32))
        return hmax + 34

    return widths, row_h


def table_height(header, rows, size):
    widths, row_h = table_layout(header, rows, size)
    return row_h(header, True) + sum(row_h(r) for r in rows)


def _table(img, s, top):
    size = s["size"]
    header, rows = s["header"], s["rows"]
    widths, row_h = table_layout(header, rows, size)
    total_h = table_height(header, rows, size)
    box = (X0, top, X1, top + total_h)
    _shadow(img, box, 24)
    tbl = Image.new("RGB", (CW, total_h), CARD)
    d = ImageDraw.Draw(tbl)
    pad = 28
    y = 0
    hh = row_h(header, True)
    tbl.paste(_gradient(CW, hh), (0, 0))
    x = 0
    for c, cell in enumerate(header):
        draw_lines(d, wrap(cell, size, widths[c] - pad * 2, base="b"), x + pad, y + 17, size, (255, 255, 255),
                   lh=1.32, base="b", bold_color=(255, 255, 255))
        x += widths[c]
    y += hh
    for ri, r in enumerate(rows):
        rh = row_h(r)
        if ri % 2:
            d.rectangle((0, y, CW, y + rh), fill=(246, 243, 253))
        x = 0
        for c in range(len(header)):
            cell = r[c] if c < len(r) else []
            draw_lines(d, wrap(cell, size, widths[c] - pad * 2, base="b" if c == 0 else "n"), x + pad, y + 17, size,
                       INK if c else PURPLE, lh=1.32, base="s" if c == 0 else "n", bold_color=PURPLE)
            x += widths[c]
        y += rh
        d.line((0, y - 1, CW, y - 1), fill=LINE, width=2)
    m = Image.new("L", tbl.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, CW - 1, total_h - 1), 24, fill=255)
    img.paste(tbl, (X0, top), m)


# --- code / template

CODE_SIZE = 29


def code_wrap(text, size=CODE_SIZE):
    f = font("c", size)
    maxw = CW - 120
    out = []
    for ln in text.split("\n"):
        if not ln:
            out.append("")
            continue
        lead = " " * (len(ln) - len(ln.lstrip()))
        cur = lead
        for word in ln.split():
            cand = (cur + " " + word) if cur.strip() else cur + word
            if f.getlength(cand) > maxw and cur.strip():
                out.append(cur)
                cur = lead + "    " + word
            else:
                cur = cand
        out.append(cur)
    return out


def _code(img, s, top):
    lines = s["lines"]
    size = CODE_SIZE
    step = int(size * 1.5)
    h = len(lines) * step + 130
    box = (X0, top, X1, min(top + h, Y1))
    card(img, box, fill=(30, 26, 48), radius=26)
    d = ImageDraw.Draw(img)
    for k, c in enumerate(((255, 95, 86), (255, 189, 46), (39, 201, 63))):
        d.ellipse((X0 + 36 + k * 34, top + 30, X0 + 56 + k * 34, top + 50), fill=c)
    d.text((X0 + 160, top + 26), s.get("label") or "", font=font("s", 24), fill=(170, 160, 200))
    y = top + 90
    for ln in lines:
        style = "cb" if ln.strip().isupper() and len(ln.strip()) > 3 else "c"
        col = (255, 196, 120) if style == "cb" else (236, 232, 248)
        d.text((X0 + 60, y), ln, font=font(style, size), fill=col)
        y += step


# --- callout (admonition)

def callout_height(items, size):
    return text_block_height(items, size) + 200


def _callout(img, s, top):
    col, default = ADMON.get(s["admon"], ADMON["note"])
    size = s["size"]
    h = callout_height(s["items"], size)
    box = (X0, top, X1, min(top + h, Y1))
    card(img, box, radius=26)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((X0, top, X1, top + 96), 26, fill=tuple(int(c * 0.14 + 255 * 0.86) for c in col))
    d.rectangle((X0, top + 60, X1, top + 96), fill=tuple(int(c * 0.14 + 255 * 0.86) for c in col))
    d.rounded_rectangle((X0, top, X0 + 16, box[3]), 8, fill=col)
    sym = {"tip": "★", "success": "✓", "danger": "!", "warning": "!", "example": "»", "quote": "“"}.get(s["admon"], "i")
    d.ellipse((X0 + 50, top + 24, X0 + 98, top + 72), fill=col)
    d.text((X0 + 74, top + 49), sym, font=font("sym", 28), fill=(255, 255, 255), anchor="mm")
    title = s.get("title") or default
    d.text((X0 + 120, top + 28), title, font=font("b", 34), fill=col)
    _draw_items(img, s["items"], X0 + 60, top + 130, CW - 120, size)


def _end(slide, meta):
    img = _gradient(W, H).copy()
    d = ImageDraw.Draw(img)
    d.text((W // 2, 300), "CHAPTER COMPLETE", font=font("b", 34), fill=(255, 236, 200), anchor="mm")
    size = 88
    while font("d", size).getlength(slide["title"]) > W - 300 and size > 50:
        size -= 4
    d.text((W // 2, 400), slide["title"], font=font("d", size), fill=(255, 255, 255), anchor="mm")
    steps = ["Do the exercises", "Run the hands-on labs", "Answer the Q&A out loud", "Explain it to someone"]
    bw, gap = 360, 30
    x = (W - (bw * 4 + gap * 3)) // 2
    for k, t in enumerate(steps):
        box = (x, 560, x + bw, 800)
        d.rounded_rectangle(box, 28, fill=(255, 255, 255))
        d.text((x + 40, 600), str(k + 1), font=font("d", 54), fill=ORANGE)
        lines = wrap([(t, "n")], 32, bw - 80, base="s")
        draw_lines(d, lines, x + 40, 676, 32, INK, lh=1.2, base="s")
        x += bw + gap
    d.text((W // 2, 960), "SYSTEM DESIGN PLAYBOOK", font=font("b", 28), fill=(255, 255, 255), anchor="mm")
    return img


def _diagram(img, s, top):
    pic = Image.open(s["png"]).convert("RGB")
    pad = 30
    maxw, maxh = CW - pad * 2, Y1 - top - pad * 2
    sc = min(maxw / pic.width, maxh / pic.height, 1.6)
    pic = pic.resize((max(1, int(pic.width * sc)), max(1, int(pic.height * sc))), Image.LANCZOS)
    bw, bh = pic.width + pad * 2, pic.height + pad * 2
    x = X0 + (CW - bw) // 2
    card(img, (x, top, x + bw, top + bh), radius=24)
    img.paste(pic, (x + pad, top + pad))


def qa_height(q, a, size):
    qh = len(wrap(q, size + 4, CW - 160, base="b")) * int((size + 4) * 1.3)
    ah = len(wrap(a, size, CW - 160)) * int(size * 1.42)
    return qh + ah + 230


def _qa(img, s, top):
    size = s["size"]
    d = ImageDraw.Draw(img)
    h = qa_height(s["q"], s["a"], size)
    box = (X0, top, X1, min(top + h, Y1))
    card(img, box)
    g = _gradient(16, int(box[3] - box[1]))
    m = Image.new("L", g.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 15, g.size[1] - 1), 8, fill=255)
    img.paste(g, (X0, top), m)
    tag = "INTERVIEW Q" + (s["num"] or "") + (f"  ·  {s['band']}" if s.get("band") else "")
    d.text((X0 + 80, top + 34), tag, font=font("b", 26), fill=ORANGE)
    y = draw_lines(d, wrap(s["q"], size + 4, CW - 160, base="b"), X0 + 80, top + 80, size + 4, INK, lh=1.3, base="b")
    if s["a"]:
        y += 20
        d.text((X0 + 80, y), "30-SECOND ANSWER", font=font("b", 24), fill=PURPLE)
        draw_lines(d, wrap(s["a"], size, CW - 160), X0 + 80, y + 44, size, INK, lh=1.42, bold_color=PURPLE)
