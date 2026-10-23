#!/usr/bin/env python3
"""
বাংলা লেখা হুবহু কি না ("য়" ফাঁদ) — Vue-র প্রতিটা বাংলা লেখা React ওয়েব (web/src) বা পুরোনো অ্যাডমিন
(server/public/admin) থেকে বাইট ধরে একই হতে হবে।

"য়" দুইভাবে লেখা যায়: এক কোড পয়েন্ট (U+09DF) অথবা য + নুকতা (U+09AF U+09BC)। দেখতে একই, কিন্তু মেলে না —
হাতে আবার টাইপ করা লেখা চুপচাপ আলাদা হয়ে যায় (পরীক্ষা, খোঁজা, সার্ভারের সাথে মেলানো ভাঙে)।

যা দেখে:
  ১. সামনে: web-vue/src আর web-vue/admin/src-এর (মন্তব্য বাদে) প্রতিটা বাংলা টুকরো — উদ্ধৃতি, টেমপ্লেট (${…}, {{ }}),
     ট্যাগ আর এস্কেপে ভাগ করে, দুই পাশের ফাঁকা বাদ দিয়ে — React/অ্যাডমিনের উৎসে হুবহু থাকতে হবে।
     লাইনে `bn-ok` লেখা থাকলে বাদ (যে লেখার আগের কোনো রূপ নেই — খুব কম রাখুন)।
  ২. উল্টো দিক (কভারেজ): React/অ্যাডমিনের প্রতিটা বাংলা টুকরো Vue-তেও থাকতে হবে — না থাকলে কোনো লেখা/পাতা বাদ পড়েছে।
     ইচ্ছে করে বাদ দেওয়া ফাইল (মরা কোড) নিচে SKIP_REACT-এ।

চালান: python3 web-vue/scripts/check-bangla.py  (রিপোর ভেতর থেকে; কিছু না মিললে exit 1)
       --forward-only  শুধু প্রথম পরীক্ষা
"""
import glob
import html
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WEBVUE = os.path.abspath(os.path.join(HERE, ".."))
ROOT = os.path.abspath(os.path.join(WEBVUE, ".."))
BENGALI = re.compile("[\u0980-\u09e5\u09f0-\u09ff]")  # বাংলা অক্ষর (শুধু ০-৯ অঙ্ক নয় — কোডের regex-এ থাকে)
# React-এ আছে কিন্তু কোথাও ব্যবহার হয় না (মরা কোড) — Vue-তে আনা হয়নি
SKIP_REACT = {
    "web/src/sections/rx/partner/stock.tsx",
}
# React ফাইলের ভেতরে মরা কোডের লেখা (কেউ ব্যবহার করে না)
SKIP_REACT_PIECES = {
    "সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন",  # services/ui.tsx NearestCall — কোথাও বসানো নেই
    "আশেপাশে কারো রক্ত লাগলে",  # Settings.tsx-এর একটা নোটিফিকেশন সুইচ — Vue-তে ধরন অনুযায়ী (lib/notifyPrefs.ts), ২০২৬-১০
    "তাঁর Lifolo অ্যাকাউন্টের ইমেইল। তাঁকে অন্তত একবার Lifolo-তে লগইন করে থাকতে হবে।",  # Care.tsx — ২০২৬-১০ থেকে অ্যাকাউন্ট না থাকলেও আমন্ত্রণ যায়
    "চালককে ফোন",  # AmbRequest.tsx চালকের বড় ফোন বাটন — Vue-তে ২০২৬-১০ থেকে দুই আইকন (কল মেনু: ফোন কল · অ্যাপ কল · ভিডিও কল)
    "অ্যাকাউন্ট মুছবেন?",  # Profile.tsx মোছার ডায়ালগ — Vue-তে ২০২৬-১০ থেকে পুরো পাতা (/delete-account, টিক দিয়ে নিশ্চিত)
    "৩০ দিন পর আপনার অ্যাকাউন্ট চিরতরে মুছে যাবে: প্রোফাইল, ছবি, ফোন নম্বর, রিকোয়েস্ট, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, ব্যাজ আর আপনার ব্যবসা। এর মধ্যে আপনার তালিকা আর নোটিফিকেশন বন্ধ থাকবে, সব ফোন থেকে লগআউট হবে। ৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে।",  # ঐ ডায়ালগের লেখা — একই কারণে
}
# টুকরো ভাগের জায়গা: উদ্ধৃতি, ব্যাকটিক, ট্যাগ, বন্ধনী, এস্কেপ, লাইন
SPLIT = re.compile(r"""\$\{|\{\{|\}\}|[`'"<>{}\\\n]""")


def strip_comments(src, vue=False):
    out = src
    if vue:
        out = re.sub(r"<!--.*?-->", " ", out, flags=re.S)
    # মন্তব্য শুরু শুধু ফাঁকা/লাইনের শুরু/বন্ধনীর পরে — "image/*" বা "https://" মন্তব্য নয়
    out = re.sub(r"(?:(?<=^)|(?<=[\s{(;,]))/\*.*?\*/", " ", out, flags=re.S | re.M)
    out = re.sub(r"(?:(?<=^)|(?<=[\s{(;,]))//[^\n]*", " ", out, flags=re.M)
    return out


def decode(src):
    # HTML এন্টিটি (&quot; …), JS এস্কেপ করা উদ্ধৃতি
    return html.unescape(src.replace("\\'", "'").replace('\\"', '"').replace("\\`", "`"))


def pieces(src):
    out = set()
    for raw_line in src.split("\n"):
        if "bn-ok" in raw_line:
            continue
        for p in SPLIT.split(raw_line):
            p = p.strip()
            # টেমপ্লেটের ভেতরের কোড (যেমন num(x)) ধরতে পারে — শুধু বাংলা অক্ষর থাকলে
            if BENGALI.search(p):
                out.add(p)
    return out


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def files(patterns):
    got = []
    for pat in patterns:
        got += glob.glob(os.path.join(ROOT, pat), recursive=True)
    return sorted(set(f for f in got if "/node_modules/" not in f))


def main():
    forward_only = "--forward-only" in sys.argv
    react_files = files([
        "web/src/**/*.ts", "web/src/**/*.tsx", "web/index.html",
        "server/public/admin/app.js", "server/public/admin/index.html",
    ])
    vue_files = files([
        "web-vue/src/**/*.ts", "web-vue/src/**/*.vue", "web-vue/index.html",
        "web-vue/admin/src/**/*.ts", "web-vue/admin/index.html",
    ])
    # React-এর পুরো লেখা (মন্তব্যসহ নয়) — টুকরো এর ভেতরে কোথাও থাকলেই চলে
    corpus_parts = []
    react_pieces = {}
    for f in react_files:
        rel = os.path.relpath(f, ROOT)
        src = strip_comments(read(f))
        corpus_parts.append(src)
        corpus_parts.append(decode(src))
        if rel not in SKIP_REACT:
            for p in pieces(decode(src)):
                if p not in SKIP_REACT_PIECES:
                    react_pieces.setdefault(p, rel)
    corpus = "\n".join(corpus_parts)

    bad = 0
    vue_corpus_parts = []
    for f in vue_files:
        rel = os.path.relpath(f, ROOT)
        src = read(f)
        clean = strip_comments(src, vue=f.endswith(".vue") or f.endswith(".html"))
        vue_corpus_parts.append(clean)
        vue_corpus_parts.append(decode(clean))
        lines = src.split("\n")
        seen = set()
        for i, line in enumerate(strip_comments(decode(src), vue=f.endswith(".vue")).split("\n")):
            if i < len(lines) and "bn-ok" in lines[i]:
                continue
            for p in pieces(line):
                if p in corpus or (rel, p) in seen:
                    continue
                seen.add((rel, p))
                hint = ""
                if "\u09df" in p and p.replace("\u09df", "\u09af\u09bc") in corpus:
                    hint = "  (এক কোড পয়েন্টের য়; React-এ য + ়)"
                elif "\u09af\u09bc" in p and p.replace("\u09af\u09bc", "\u09df") in corpus:
                    hint = "  (য + ়; React-এ এক কোড পয়েন্টের য়)"
                print(f"{rel}:{i + 1}: React/অ্যাডমিনে নেই: {p!r}{hint}")
                bad += 1

    missing = 0
    if not forward_only:
        vue_corpus = "\n".join(vue_corpus_parts)
        for p, rel in sorted(react_pieces.items(), key=lambda x: (x[1], x[0])):
            if p not in vue_corpus:
                print(f"{rel}: Vue-তে নেই: {p!r}")
                missing += 1

    total = len(react_pieces)
    print(f"\nVue ফাইল {len(vue_files)}টা, React/অ্যাডমিন ফাইল {len(react_files)}টা; React-এর বাংলা টুকরো {total}টা, "
          f"Vue-তে আছে {total - missing}টা ({100.0 * (total - missing) / max(1, total):.2f}%).")
    if bad or missing:
        print(f"হুবহু মেলেনি: {bad}টা; Vue-তে বাদ: {missing}টা। React/অ্যাডমিন থেকে কপি করুন (নতুন লেখা হলে লাইনে bn-ok)।")
        return 1
    print("বাংলা লেখা ঠিক আছে।")
    return 0


if __name__ == "__main__":
    sys.exit(main())
