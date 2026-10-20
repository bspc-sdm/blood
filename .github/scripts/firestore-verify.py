#!/usr/bin/env python3
# Firestore export ফাইল (json.gz) খুলে যাচাই — VPS-এ চলে (firestore-off.yml), আর push-এর পরীক্ষাতেও।
#   python3 - <file.json.gz>  → stdout-এ এক লাইন JSON: total, groups (collection-এর নমুনা → সংখ্যা), sha256, bytes
# কোনো নথির তথ্য বা ID ছাপা হয় না।
import gzip
import hashlib
import json
import sys

p = sys.argv[1]
h = hashlib.sha256()
size = 0
with open(p, 'rb') as f:
    for chunk in iter(lambda: f.read(1 << 20), b''):
        h.update(chunk)
        size += len(chunk)
with gzip.open(p, 'rt', encoding='utf-8') as f:
    d = json.load(f)
groups = {}
for doc in d['documents']:
    parts = doc['path'].split('/')
    pat = '/'.join('*' if i % 2 else s for i, s in enumerate(parts[:-1]))
    groups[pat] = groups.get(pat, 0) + 1
print(json.dumps({'ok': True, 'total': len(d['documents']), 'groups': groups, 'sha256': h.hexdigest(), 'bytes': size,
                  'project': d.get('project'), 'format': d.get('format')}, sort_keys=True))
