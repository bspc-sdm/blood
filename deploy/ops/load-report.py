#!/usr/bin/env python3
"""
লোড টেস্টের ফল একসাথে করে বাংলা সারাংশ (Markdown) আর key=value।
args: <dir>  — দরকারি ফাইল (workflow বানায়):
  stages.tsv        idx<TAB>rate<TAB>start_epoch<TAB>end_epoch<TAB>duration_s
  stage-<idx>.json  k6 handleSummary (পুরো data)
  stage-<idx>.log   k6-র লগ (ERRSAMPLE / PROBE / WROTE / SKIP লাইন)
  metrics.csv       VPS-এর মাপ (vps-ops.sh sample-start)
  config.txt, before.txt, window.txt, cleanup.txt, limiter.txt  key=value (থাকলে)
লেখে: <dir>/summary.md, <dir>/load-status.txt
"""
import csv
import json
import os
import re
import sys
from collections import Counter

D = sys.argv[1]
P95_LIMIT = 1000.0
ERR_LIMIT = 0.01


def kv(name):
    out = {}
    try:
        with open(os.path.join(D, name), encoding="utf-8") as f:
            for line in f:
                if "=" in line:
                    k, v = line.rstrip("\n").split("=", 1)
                    out[k.strip()] = v.strip()
    except OSError:
        pass
    return out


def metric(m, name, field):
    v = m.get(name, {}).get("values", {}).get(field)
    return None if v is None else float(v)


def fmt_ms(v):
    return "—" if v is None else f"{v:,.0f}"


def fmt_pct(v):
    return "—" if v is None else f"{v * 100:.2f}%"


stages = []
try:
    with open(os.path.join(D, "stages.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if len(p) >= 5:
                stages.append({"idx": int(p[0]), "rate": int(p[1]), "start": int(p[2]), "end": int(p[3]), "dur": float(p[4])})
except OSError:
    pass

rows = []
try:
    with open(os.path.join(D, "metrics.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                rows.append({k: float(v) if v not in ("", None) else 0.0 for k, v in r.items()})
            except ValueError:
                continue
except OSError:
    pass

errsamples = Counter()
probe = None
wrote = Counter()
skips = Counter()
for s in stages:
    path = os.path.join(D, f"stage-{s['idx']}.log")
    if not os.path.exists(path):
        continue
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.search(r'msg="(.*)" source=console', line)
            if not m:
                continue
            try:
                msg = json.loads('"' + m.group(1) + '"')
            except ValueError:
                msg = m.group(1)
            if msg.startswith("ERRSAMPLE "):
                try:
                    e = json.loads(msg[10:])
                    errsamples[f"{e.get('k')}/{e.get('a')} HTTP {e.get('s')} {str(e.get('e'))[:70]}"] += 1
                except ValueError:
                    errsamples[msg[:100]] += 1
            elif msg.startswith("PROBE ") and probe is None:
                try:
                    probe = json.loads(msg[6:])
                except ValueError:
                    pass
            elif msg.startswith("WROTE "):
                wrote[msg.split()[1]] += 1
            elif msg.startswith("SKIP "):
                skips[msg[5:70]] += 1

results = []
for s in stages:
    try:
        with open(os.path.join(D, f"stage-{s['idx']}.json"), encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        continue
    m = data.get("metrics", {})
    n = metric(m, "lf_latency", "count") or 0
    r = {
        **s,
        "rps": n / s["dur"] if s["dur"] else 0,
        "p95": metric(m, "lf_latency", "p(95)"),
        "p99": metric(m, "lf_latency", "p(99)"),
        "err": metric(m, "lf_errors", "rate"),
        "dropped": metric(m, "dropped_iterations", "count") or 0,
        "vus_max": metric(m, "vus_max", "value") or metric(m, "vus", "max"),
    }
    for k in ("read", "write", "guest", "login", "health"):
        r[f"p95_{k}"] = metric(m, f"lf_latency{{kind:{k}}}", "p(95)")
        r[f"med_{k}"] = metric(m, f"lf_latency{{kind:{k}}}", "med")
        r[f"err_{k}"] = metric(m, f"lf_errors{{kind:{k}}}", "rate")
        r[f"n_{k}"] = metric(m, f"lf_latency{{kind:{k}}}", "count")
    w = [x for x in rows if s["start"] <= x.get("ts", 0) <= s["end"] + 2]

    def agg(col, fn):
        vals = [x[col] for x in w if col in x]
        return fn(vals) if vals else None

    r["cpu_avg"] = agg("cpu", lambda v: sum(v) / len(v))
    r["cpu_max"] = agg("cpu", max)
    r["load_max"] = agg("load1", max)
    r["mem_max"] = agg("mem_used_mb", max)
    r["avail_min"] = agg("mem_avail_mb", min)
    r["swap_max"] = agg("swap_used_mb", max)
    r["rss_max"] = agg("java_rss_mb", max)
    r["thr_max"] = agg("java_threads", max)
    r["jcpu_max"] = agg("java_cpu", max)
    r["pg_active_max"] = agg("pg_active", max)
    r["pg_total_max"] = agg("pg_total", max)
    r["pg_lock_max"] = agg("pg_lock_wait", max)
    r["c8080_max"] = agg("tcp_8080", max)
    r["c443_max"] = agg("tcp_443", max)
    r["bad"] = (r["p95"] is not None and r["p95"] > P95_LIMIT) or (r["err"] is not None and r["err"] > ERR_LIMIT)
    results.append(r)

cfg = kv("config.txt")
before = kv("before.txt")
win = kv("window.txt")
clean = kv("cleanup.txt")
lim = kv("limiter.txt")

good = [r for r in results if not r["bad"]]
bad = [r for r in results if r["bad"]]
best = max(good, key=lambda r: r["rps"]) if good else None
knee = bad[0] if bad else None

st = {}
st["stages_run"] = len(results)
st["safe_rps"] = f"{best['rps']:.1f}" if best else "none"
st["safe_target"] = best["rate"] if best else "none"
st["safe_p95_ms"] = fmt_ms(best["p95"]) if best else "—"
st["knee_target"] = knee["rate"] if knee else "not reached"
st["knee_p95_ms"] = fmt_ms(knee["p95"]) if knee else "—"
st["knee_err"] = fmt_pct(knee["err"]) if knee else "—"
st["cleanup"] = clean.get("cleanup", "?")
st["api_restarted"] = "yes" if before.get("api_pid") and win.get("api_pid") and before.get("api_pid") != win.get("api_pid") else "no"

L = []
a = L.append
a("## Lifolo — লোড টেস্ট (সরাসরি VPS-এ, Cloudflare ছাড়া)")
a("")
if best:
    a(f"**নিরাপদ ক্ষমতা:** ~**{best['rps']:.0f} অনুরোধ/সেকেন্ড** (লক্ষ্য {best['rate']} লগইন-করা rps + অতিথি/লগইন/health) — "
      f"p95 {fmt_ms(best['p95'])} ms, ত্রুটি {fmt_pct(best['err'])}।")
else:
    a("**নিরাপদ ক্ষমতা:** প্রথম ধাপেই সীমা পেরিয়েছে (নিচের সারণি দেখুন)।")
if knee:
    why = []
    if knee["p95"] is not None and knee["p95"] > P95_LIMIT:
        why.append(f"p95 {fmt_ms(knee['p95'])} ms > 1000")
    if knee["err"] is not None and knee["err"] > ERR_LIMIT:
        why.append(f"ত্রুটি {fmt_pct(knee['err'])} > 1%")
    a(f"**ভাঙনের বিন্দু:** লক্ষ্য {knee['rate']} rps-এ ({', '.join(why)}) — এখানে থামানো হয়েছে।")
else:
    a("**ভাঙনের বিন্দু:** দেওয়া সর্বোচ্চ ধাপ পর্যন্ত পৌঁছায়নি (p95 ≤ 1 s, ত্রুটি ≤ 1%)।")
a("")
a("### ধাপ অনুযায়ী")
a("| ধাপ | লক্ষ্য rps | পাওয়া rps | p95 সব (ms) | p95 পড়া | p95 লেখা | p95 অতিথি | p95 health | ত্রুটি | dropped | CPU গড়/সর্বোচ্চ | Java RSS (MB) | DB সংযোগ active/মোট |")
a("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in results:
    mark = " ❌" if r["bad"] else ""
    a(f"| {r['idx']}{mark} | {r['rate']} | {r['rps']:.1f} | {fmt_ms(r['p95'])} | {fmt_ms(r['p95_read'])} | {fmt_ms(r['p95_write'])} | "
      f"{fmt_ms(r['p95_guest'])} | {fmt_ms(r['p95_health'])} | {fmt_pct(r['err'])} | {int(r['dropped'])} | "
      f"{fmt_ms(r['cpu_avg'])}% / {fmt_ms(r['cpu_max'])}% | {fmt_ms(r['rss_max'])} | {fmt_ms(r['pg_active_max'])}/{fmt_ms(r['pg_total_max'])} |")
a("")
a("**লক্ষ্য rps** = লগইন-করা ডেমো ব্যবহারকারীর অনুরোধ (৯৫% পড়া, ৫% লেখা); তার সাথে প্রতি ধাপে স্থির: অতিথি ২/সে "
  "(সার্ভারের সীমা প্রতি IP-তে মিনিটে ২৪০), লগইন-কোডের পথ ০.৫/সে, health ১/সে। প্রতি ধাপ "
  f"{int(results[0]['dur']) if results else '?'} সেকেন্ড। **dropped** = k6 সময়মতো পাঠাতে পারেনি (সার্ভার ধীর হলে বাড়ে)।")
a("")
if results:
    a("### VPS (ধাপের সর্বোচ্চ)")
    a("| ধাপ | load1 | RAM ব্যবহৃত (MB) | খালি RAM কম-সে-কম (MB) | swap (MB) | Java CPU % (সব কোর) | Java থ্রেড | nginx→অ্যাপ সংযোগ | :443 সংযোগ | DB lock-অপেক্ষা |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        a(f"| {r['idx']} | {r['load_max'] if r['load_max'] is not None else '—'} | {fmt_ms(r['mem_max'])} | {fmt_ms(r['avail_min'])} | {fmt_ms(r['swap_max'])} | "
          f"{fmt_ms(r['jcpu_max'])} | {fmt_ms(r['thr_max'])} | {fmt_ms(r['c8080_max'])} | {fmt_ms(r['c443_max'])} | {fmt_ms(r['pg_lock_max'])} |")
    a("")
    a("### ধরন অনুযায়ী (মাঝামাঝি / p95, ms)")
    a("| ধাপ | পড়া | লেখা | অতিথি | লগইন-কোড | health |")
    a("|---|---|---|---|---|---|")
    for r in results:
        a("| " + str(r["idx"]) + " | " + " | ".join(
            f"{fmt_ms(r['med_' + k])} / {fmt_ms(r['p95_' + k])} ({fmt_pct(r['err_' + k])})" for k in ("read", "write", "guest", "login", "health")) + " |")
    a("")
a("### সার্ভারের দিক (পুরো টেস্টের সময়)")
a(f"- nginx: {win.get('nginx_requests', '?')} অনুরোধ; 5xx {win.get('nginx_5xx', '?')} (502: {win.get('nginx_502', '?')}, 504: {win.get('nginx_504', '?')}), "
  f"499 (ক্লায়েন্ট ছেড়ে দিয়েছে) {win.get('nginx_499', '?')}, 429 {win.get('nginx_429', '?')}; error.log-এ নতুন {win.get('nginx_errors', '?')} লাইন "
  f"(upstream timeout {win.get('nginx_upstream_timeouts', '?')}, worker_connections কম {win.get('nginx_worker_conns_short', '?')})")
a(f"- অ্যাপ: লগে ERROR/Exception {win.get('app_errors', '?')}টা, Hikari পুল-টাইমআউট {win.get('app_hikari_timeouts', '?')}, OutOfMemory {win.get('app_oom', '?')}; "
  f"টেস্টের মধ্যে অ্যাপ restart: {'হ্যাঁ ⚠️' if st['api_restarted'] == 'yes' else 'না'}")
try:
    with open(os.path.join(D, "app.top"), encoding="utf-8") as f:
        top = [x.rstrip() for x in f if x.strip()]
    if top:
        a("- অ্যাপের লগে সবচেয়ে বেশি আসা সতর্কতা/ত্রুটি:")
        a("```")
        L.extend(top[:8])
        a("```")
except OSError:
    pass
if errsamples:
    a("- ক্লায়েন্টে দেখা ত্রুটির নমুনা (প্রতি VU সর্বোচ্চ ৩টা):")
    a("```")
    for k, n in errsamples.most_common(10):
        a(f"{n:4d}  {k}")
    a("```")
if probe and probe.get("dropped"):
    a(f"- শুরুর যাচাইয়ে বাদ পড়া action (এই ডেটায় চলে না, ত্রুটি গোনা হয়নি): {'; '.join(probe['dropped'])}")
a(f"- লেখা: রক্তের অনুরোধ বানানো→মোছা {wrote.get('blood', 0)} বার, অ্যাম্বুলেন্স ডাকা→বাতিল {wrote.get('amb', 0)} বার"
  + (f"; বাদ: {', '.join(f'{k} ×{v}' for k, v in skips.items())}" if skips else ""))
if lim:
    a(f"- অতিথির সীমা (একই IP থেকে দ্রুত {lim.get('sent', '?')}টা): 200 = {lim.get('ok', '?')}, 429 = {lim.get('limited', '?')}, অন্য = {lim.get('other', '?')} "
      f"→ {'সীমা কাজ করে ✅' if lim.get('limited', '0') not in ('0', '?') else 'সীমা দেখা যায়নি ⚠️'}")
a("")
a("### পরিষ্কার (শুধু demo-… অ্যাকাউন্ট)")
c = {k[6:]: v for k, v in clean.items() if k.startswith("clean_")}
a(f"- ফল: **{clean.get('cleanup', '?')}** — " + (", ".join(f"{k} {v}" for k, v in c.items()) or "কিছু মোছার দরকার হয়নি")
  + f"; বাকি থাকা LOADTEST সারি: রক্ত {clean.get('left_blood', '?')}, অ্যাম্বুলেন্স {clean.get('left_amb', '?')}")
a("")
a("### এখনকার সেটিং")
a(f"- JVM: `{cfg.get('execstart', '?')}` · {cfg.get('java_version', '')} · অ্যাপের RSS এখন {cfg.get('api_rss_mb', '?')} MB · open files {cfg.get('api_nofile_limit', '?')}")
a(f"- Hikari পুল: {cfg.get('db_pool_size', '?')} · virtual threads: {cfg.get('virtual_threads', '?')}")
a(f"- PostgreSQL {cfg.get('pg_version', '?')}: max_connections {cfg.get('pg_max_connections', '?')}, shared_buffers {cfg.get('pg_shared_buffers', '?')}, "
  f"effective_cache_size {cfg.get('pg_effective_cache_size', '?')}, work_mem {cfg.get('pg_work_mem', '?')}, ডাটাবেস {cfg.get('pg_db_size', '?')}")
a(f"- nginx: worker_processes {cfg.get('nginx_worker_processes', '?')}, worker_connections {cfg.get('nginx_worker_connections', '?')}, "
  f"upstream keepalive {'আছে' if cfg.get('nginx_upstream_keepalive', '0') not in ('0', '') else 'নেই'}, limit_req {'আছে' if cfg.get('nginx_limit_req', '0') not in ('0', '') else 'নেই'}")
a(f"- মেশিন: {cfg.get('cpus', '?')} vCPU, RAM {cfg.get('mem_total_mb', '?')} MB, swap {cfg.get('swap_total_mb', '?')} MB · Redis maxmemory {cfg.get('redis_maxmemory', '?')}")

with open(os.path.join(D, "summary.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(L) + "\n")
with open(os.path.join(D, "load-status.txt"), "w", encoding="utf-8") as f:
    for k, v in st.items():
        f.write(f"{k}={v}\n")
    for r in results:
        f.write(f"stage_{r['idx']}=target {r['rate']} rps {r['rps']:.1f} p95 {fmt_ms(r['p95'])} err {fmt_pct(r['err'])} cpu_max {fmt_ms(r['cpu_max'])} pg_active_max {fmt_ms(r['pg_active_max'])}\n")
print("\n".join(L))
