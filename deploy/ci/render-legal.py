#!/usr/bin/env python3
"""PRIVACY.md / TERMS.md → সাধারণ HTML পাতা (lifolo.com/privacy, lifolo.com/terms)।

Deploy backend workflow ওয়েবসাইট বানানোর সময় চালায় (বাইরের কোনো প্যাকেজ লাগে না):
    python3 deploy/ci/render-legal.py PRIVACY.md out/privacy/index.html --kind privacy
    python3 deploy/ci/render-legal.py TERMS.md   out/terms/index.html   --kind terms

ফাইলগুলোতে যা আছে শুধু সেটুকু Markdown: # / ## শিরোনাম, অনুচ্ছেদ, - তালিকা (দুই ঘর ভেতরে = ভেতরের তালিকা),
| টেবিল |, ---, **মোটা**, `কোড`, [লেখা](লিংক), খালি ইমেইল/https লিংক। সব লেখা HTML-escape করা।
শিরোনামের id GitHub-এর মতো (পুরোনো লিংকের #অংশ মেলে)। পাতায় কোনো স্ক্রিপ্ট নেই; CSS ভেতরেই (CSP: style-src 'unsafe-inline')।
"""
import argparse
import html
import re
import sys

PAGES = {
    "privacy": ("প্রাইভেসি পলিসি · Privacy Policy", "/terms", "ব্যবহারের শর্ত · Terms of Use"),
    "terms": ("ব্যবহারের শর্ত · Terms of Use", "/privacy", "প্রাইভেসি পলিসি · Privacy Policy"),
}


def inline(text: str) -> str:
    out = []
    pos = 0
    # [লেখা](url) | `code` | **bold** | email | url
    pat = re.compile(
        r"\[([^\]]+)\]\((https?://[^)\s]+|mailto:[^)\s]+|/[^)\s]*)\)"
        r"|`([^`]+)`"
        r"|\*\*(.+?)\*\*"
        r"|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})"
        r"|(https?://[^\s<>()]+[^\s<>().,;:!?'\"])"
    )
    for m in pat.finditer(text):
        out.append(html.escape(text[pos:m.start()], quote=False))
        if m.group(1) is not None:
            out.append(f'<a href="{html.escape(m.group(2))}">{inline(m.group(1))}</a>')
        elif m.group(3) is not None:
            out.append(f"<code>{html.escape(m.group(3), quote=False)}</code>")
        elif m.group(4) is not None:
            out.append(f"<strong>{inline(m.group(4))}</strong>")
        elif m.group(5) is not None:
            e = html.escape(m.group(5))
            out.append(f'<a href="mailto:{e}">{e}</a>')
        else:
            u = html.escape(m.group(6))
            out.append(f'<a href="{u}">{u}</a>')
        pos = m.end()
    out.append(html.escape(text[pos:], quote=False))
    return "".join(out)


def slug(text: str, seen: dict) -> str:
    # GitHub: ছোট হাতের, যতিচিহ্ন বাদ (অক্ষর, সংখ্যা, বাংলা কার/চিহ্ন, _ আর - থাকে), ফাঁকা → -
    s = "".join(ch for ch in text.strip().lower() if ch.isalnum() or ch in "-_ " or "ঀ" <= ch <= "৿")
    s = s.replace(" ", "-")
    n = seen.get(s, 0)
    seen[s] = n + 1
    return s if n == 0 else f"{s}-{n}"


def cells(line: str):
    t = line.strip()
    if t.startswith("|"):
        t = t[1:]
    if t.endswith("|"):
        t = t[:-1]
    return [c.strip() for c in t.split("|")]


def render(md: str):
    lines = md.replace("\r\n", "\n").split("\n")
    body, title, seen = [], "", {}
    para = []
    i = 0

    def flush():
        if para:
            body.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()

    while i < len(lines):
        line = lines[i]
        s = line.strip()
        if not s:
            flush()
            i += 1
            continue
        m = re.match(r"^(#{1,3})\s+(.*)$", s)
        if m:
            flush()
            lvl, txt = len(m.group(1)), m.group(2).strip()
            if lvl == 1 and not title:
                title = txt
            body.append(f'<h{lvl} id="{html.escape(slug(txt, seen))}">{inline(txt)}</h{lvl}>')
            i += 1
            continue
        if re.match(r"^(-{3,}|\*{3,})$", s):
            flush()
            body.append("<hr>")
            i += 1
            continue
        if s.startswith("|") and i + 1 < len(lines) and re.match(r"^\|?\s*:?-{2,}", lines[i + 1].strip()):
            flush()
            head = cells(s)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(cells(lines[i]))
                i += 1
            t = ['<div class="table"><table><thead><tr>']
            t += [f"<th>{inline(c)}</th>" for c in head]
            t.append("</tr></thead><tbody>")
            for r in rows:
                r = (r + [""] * len(head))[: len(head)]
                t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            body.append("".join(t))
            continue
        if re.match(r"^\s*[-*]\s+", line):
            flush()
            # তালিকা: ঘরের ভেতরে ঢোকানো অনুযায়ী ভেতরের তালিকা; পরের লাইন (ফাঁকা ছাড়া, - ছাড়া) আগের পদের অংশ
            items = []  # (depth, text)
            while i < len(lines) and lines[i].strip():
                l = lines[i]
                mm = re.match(r"^(\s*)[-*]\s+(.*)$", l)
                if mm:
                    items.append([len(mm.group(1).expandtabs(4)) // 2, mm.group(2)])
                elif items:
                    items[-1][1] += " " + l.strip()
                i += 1
            out, depth = [], -1
            for d, txt in items:
                d = min(d, depth + 1)
                if d > depth:
                    out.append("<ul>" * (d - depth))
                elif d < depth:
                    out.append("</li>" + "</ul></li>" * (depth - d))
                else:
                    out.append("</li>")
                depth = d
                out.append("<li>" + inline(txt))
            out.append("</li>" + "</ul></li>" * depth + "</ul>")
            body.append("".join(out))
            continue
        para.append(s)
        i += 1
    flush()
    return title, "\n".join(body)


CSS = """
:root{color-scheme:light dark;--bg:#fff;--fg:#1d1b20;--muted:#5f5b66;--line:#e3dfe8;--head:#f6f3f9;--link:#b3261e}
@media (prefers-color-scheme:dark){:root{--bg:#141218;--fg:#e8e4ec;--muted:#aaa5b1;--line:#36323c;--head:#1f1c24;--link:#ffb4ab}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.65 "Hind Siliguri","Noto Sans Bengali","Noto Sans",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header,main,footer{max-width:820px;margin:0 auto;padding:0 16px}
header{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center;justify-content:space-between;padding-top:16px;padding-bottom:12px;border-bottom:1px solid var(--line)}
header a.brand{font-weight:700;font-size:20px;color:var(--fg);text-decoration:none}
header nav{display:flex;flex-wrap:wrap;gap:4px 14px;font-size:14px}
a{color:var(--link)}
h1{font-size:24px;line-height:1.35;margin:24px 0 8px}
h2{font-size:19px;margin:28px 0 8px;padding-top:4px}
p,li{overflow-wrap:anywhere}
ul{padding-left:22px}
li{margin:4px 0}
hr{border:0;border-top:1px solid var(--line);margin:32px 0}
code{font-size:.9em;background:var(--head);padding:1px 4px;border-radius:4px}
.table{overflow-x:auto;margin:12px 0;border:1px solid var(--line);border-radius:8px}
table{border-collapse:collapse;width:100%;min-width:560px;font-size:14px;line-height:1.5}
th,td{text-align:left;vertical-align:top;padding:8px 10px;border-bottom:1px solid var(--line)}
th{background:var(--head);font-weight:600}
tr:last-child td{border-bottom:0}
footer{color:var(--muted);font-size:13px;padding-top:24px;padding-bottom:32px}
"""


def page(kind: str, title: str, body: str) -> str:
    name, other_href, other_name = PAGES[kind]
    return f"""<!doctype html>
<html lang="bn">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title or name)}</title>
<meta name="description" content="Lifolo — {html.escape(name)}">
<link rel="icon" href="/w/icon.svg" type="image/svg+xml">
<style>{CSS}</style>
</head>
<body>
<header><a class="brand" href="/">Lifolo</a><nav><a href="{other_href}">{html.escape(other_name)}</a><a href="/download/lifolo.apk">অ্যাপ নামান · Download</a></nav></header>
<main>
{body}
</main>
<footer>Lifolo · <a href="/">lifolo.com</a> · <a href="mailto:ekapkbd@gmail.com">ekapkbd@gmail.com</a></footer>
</body>
</html>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("out")
    ap.add_argument("--kind", choices=sorted(PAGES), required=True)
    a = ap.parse_args()
    with open(a.src, encoding="utf-8") as f:
        title, body = render(f.read())
    if not title or "<h2" not in body:
        sys.exit(f"{a.src}: no title/sections — nothing rendered")
    import os
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(page(a.kind, title, body))
    print(f"{a.src} → {a.out} ({len(body)} chars)")


if __name__ == "__main__":
    main()
