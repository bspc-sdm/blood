#!/usr/bin/env python3
"""
"য়" check: every Bangla piece of text in the Kotlin sources must be a byte-for-byte copy of the Node source.

"য়" can be written as one code point (U+09DF) or as য + nukta (U+09AF U+09BC). Both look the same but do not
compare equal, so a retyped message silently differs from what the Node server (and the apps' tests) send.
The Node code uses the two-code-point form almost everywhere.

What it checks: each Kotlin string literal under backend/src/main/kotlin that contains Bengali letters is split
at string templates (${...}, $name) and escapes (\\n …); every piece with a Bengali letter must occur verbatim in
some file under server/lib or server/api. A line with the comment `// bn-ok` is skipped (text that has no
Node original, e.g. a new message — keep those rare).

Usage: python3 backend/scripts/check-bangla.py  (from the repo root; exit code 1 when something does not match)
"""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BENGALI = re.compile("[ঀ-৿]")


def node_corpus():
    texts = []
    for pat in ("server/lib/**/*.js", "server/api/**/*.js", "server/lib/**/*.json"):
        for f in glob.glob(os.path.join(ROOT, pat), recursive=True):
            with open(f, encoding="utf-8") as fh:
                t = fh.read()
            texts.append(t)
            # JS sources escape quotes differently from Kotlin
            texts.append(t.replace("\\'", "'").replace('\\"', '"').replace("\\`", "`"))
    return "\n".join(texts)


def kotlin_literals(src):
    """yields (line_no, literal_body, is_raw) for every string literal outside comments"""
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            while j + 3 < n and src[j + 3] == '"':
                j += 1
            yield line, src[i + 3:j], True
            line += src.count("\n", i, j)
            i = j + 3
        elif c == '"':
            j = i + 1
            depth = 0
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src.startswith("${", j):
                    depth += 1
                    j += 2
                    continue
                if depth and src[j] == "}":
                    depth -= 1
                elif not depth and src[j] == '"':
                    break
                elif src[j] == "\n":
                    break
                j += 1
            yield line, src[i + 1:j], False
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
        else:
            i += 1


def pieces(body, raw):
    """split a literal at templates and escapes"""
    out, cur, i, n = [], [], 0, len(body)
    while i < n:
        if body.startswith("${", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if body[j] == "{":
                    depth += 1
                elif body[j] == "}":
                    depth -= 1
                j += 1
            out.append("".join(cur))
            cur = []
            i = j
        elif body[i] == "$" and i + 1 < n and (body[i + 1].isalpha() or body[i + 1] == "_"):
            j = i + 1
            while j < n and (body[j].isalnum() or body[j] == "_"):
                j += 1
            out.append("".join(cur))
            cur = []
            i = j
        elif not raw and body[i] == "\\" and i + 1 < n:
            e = body[i + 1]
            if e in "\"'\\$":
                cur.append(e)
                i += 2
            else:  # \n, \t, \u…: split here
                out.append("".join(cur))
                cur = []
                i += 6 if e == "u" else 2
        else:
            cur.append(body[i])
            i += 1
    out.append("".join(cur))
    return [p for p in out if BENGALI.search(p)]


def main():
    corpus = node_corpus()
    bad = 0
    files = sorted(glob.glob(os.path.join(ROOT, "backend/src/main/kotlin/**/*.kt"), recursive=True))
    for f in files:
        with open(f, encoding="utf-8") as fh:
            src = fh.read()
        lines = src.split("\n")
        for line_no, body, raw in kotlin_literals(src):
            if not BENGALI.search(body):
                continue
            if "// bn-ok" in lines[line_no - 1]:
                continue
            for p in pieces(body, raw):
                if p in corpus:
                    continue
                rel = os.path.relpath(f, ROOT)
                hint = ""
                if "য়" in p and p.replace("য়", "য়") in corpus:
                    hint = "  (one-code-point য়; Node writes য + ়)"
                elif "য়" in p and p.replace("য়", "য়") in corpus:
                    hint = "  (য + ়; Node writes the one-code-point য়)"
                print(f"{rel}:{line_no}: not in server/: {p!r}{hint}")
                bad += 1
    if bad:
        print(f"\n{bad} Bangla text piece(s) do not match the Node source byte-for-byte. Copy them from server/ "
              "(or add `// bn-ok` to the line when the text has no Node original).")
        return 1
    print(f"Bangla texts OK ({len(files)} Kotlin files checked against server/).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
