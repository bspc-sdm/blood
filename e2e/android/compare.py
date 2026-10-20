#!/usr/bin/env python3
"""দুই সার্ভারে (Node আর Spring) একই Maestro যাত্রার ফল পাশাপাশি: summary.md
ব্যবহার: compare.py <node-out> <spring-out> <summary.md>
প্রতিটা যাত্রার rc, ধাপের অবস্থা (COMPLETED / SKIPPED / WARNED / FAILED …), আর যে ধাপের অবস্থা দুই দিকে আলাদা।"""
import json
import os
import sys
from collections import Counter


def results(out):
    r = {}
    p = os.path.join(out, "results.txt")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8"):
            parts = line.split()
            if len(parts) >= 2 and parts[1].startswith("rc="):
                r[parts[0]] = (parts[1][3:], parts[2] if len(parts) > 2 else "")
    return r


def describe(cmd):
    if not cmd:
        return "?"
    name, body = next(iter(cmd.items()))
    name = name.replace("Command", "")
    if isinstance(body, dict):
        for k in ("selector", "condition", "path", "text", "flowPath", "sourceDescription"):
            v = body.get(k)
            if v:
                s = json.dumps(v, ensure_ascii=False)
                return f"{name} {s[:90]}"
    return name


def steps(out, flow):
    # Maestro রাখে debug/<flow>/.maestro/tests/<সময়>/<flow>/commands.json (লুকানো ফোল্ডার — glob ধরে না)
    files = sorted(os.path.join(d, "commands.json") for d, _, fs in os.walk(os.path.join(out, "debug", flow)) if "commands.json" in fs)
    if not files:
        return []
    try:
        data = json.load(open(files[-1], encoding="utf-8"))
    except Exception:
        return []
    rows = []
    for c in data:
        md = c.get("metadata", {})
        rows.append((md.get("sequenceNumber"), md.get("depth", 0), describe(c.get("command", {})), md.get("status")))
    return rows


def main():
    node, spring, dst = sys.argv[1:4]
    rn, rs = results(node), results(spring)
    flows = sorted(set(rn) | set(rs))
    lines = ["# Android e2e — Node (Vercel) vs Spring", ""]
    for label, out in (("node", node), ("spring", spring)):
        p = os.path.join(out, "server.txt")
        if os.path.exists(p):
            lines.append(f"- **{label}**: " + open(p, encoding="utf-8").read().strip().replace("\n", " · "))
    lines += ["", "| journey | node rc | spring rc | node steps | spring steps | parity |", "|---|---|---|---|---|---|"]
    detail = []
    verdict_ok = True
    for f in flows:
        a, b = rn.get(f, ("-", "")), rs.get(f, ("-", ""))
        sa, sb = steps(node, f), steps(spring, f)
        ca, cb = Counter(s[3] for s in sa), Counter(s[3] for s in sb)
        fmt = lambda c: " ".join(f"{k}:{v}" for k, v in sorted(c.items(), key=lambda kv: str(kv[0])))
        da = [(s[2], s[3]) for s in sa]
        db = [(s[2], s[3]) for s in sb]
        same_rc = a[0] == b[0]
        worse = a[0] == "0" and b[0] != "0"
        if worse:
            verdict_ok = False
        diff = []
        n = max(len(da), len(db))
        for i in range(n):
            x = da[i] if i < len(da) else ("-", "-")
            y = db[i] if i < len(db) else ("-", "-")
            if x[1] != y[1]:
                diff.append(f"  - #{i} node `{x[1]}` {x[0]} | spring `{y[1]}` {y[0]}")
        par = "same" if same_rc and not diff else ("SPRING WORSE" if worse else ("rc differs" if not same_rc else f"{len(diff)} step(s) differ"))
        lines.append(f"| {f} | {a[0]} ({a[1]}) | {b[0]} ({b[1]}) | {fmt(ca)} | {fmt(cb)} | {par} |")
        if diff:
            detail += [f"### {f}", *diff[:60], ""]
    lines += ["", "**Verdict:** " + ("PARITY — Spring fails no journey that passes on Node" if verdict_ok else "Spring fails a journey that passes on Node"), ""]
    lines += ["rc 0 = journey passed (optional steps may still be WARNED/SKIPPED; those are listed below when they differ).", ""]
    if detail:
        lines += ["## Steps whose status differs", "", *detail]
    open(dst, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
