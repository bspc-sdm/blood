# স্ক্রিনশট গ্যালারির জন্য ছোট করা: shots/**/*.png → <out>/shots/**/*.webp (মান ৭০), manifest.json-এ ফাইলের নাম বদলে।
# চালান: python3 e2e/web/export.py <out-dir>
import json
import os
import sys

from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, 'shots')
out = sys.argv[1]
os.makedirs(os.path.join(out, 'shots'), exist_ok=True)
manifest = json.load(open(os.path.join(src, 'manifest.json'), encoding='utf-8'))
total = 0
for m in manifest:
    png = os.path.join(src, m['file'])
    webp_rel = m['file'][:-4] + '.webp'
    dst = os.path.join(out, 'shots', webp_rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    Image.open(png).convert('RGB').save(dst, 'WEBP', quality=70, method=6)
    total += os.path.getsize(dst)
    m['file'] = 'shots/' + webp_rel
json.dump(manifest, open(os.path.join(out, 'manifest.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(f'{len(manifest)} shots, {total / 1e6:.1f} MB')
