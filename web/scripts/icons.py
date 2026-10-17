# অ্যাপের Android vector আইকনগুলো (app/src/main/res/drawable/ic_*.xml) থেকে ওয়েবের আইকন তালিকা বানায়।
# চালান: python3 web/scripts/icons.py  →  web/src/icons/paths.ts
import glob, json, os, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
root = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res', 'drawable')
out = {}
for f in sorted(glob.glob(os.path.join(root, 'ic_*.xml'))):
    t = ET.parse(f).getroot()
    if t.tag != 'vector':
        continue
    name = os.path.basename(f)[3:-4]
    vw, vh = t.get(A + 'viewportWidth'), t.get(A + 'viewportHeight')
    ty = 0
    for g in t.iter('group'):
        ty = float(g.get(A + 'translateY') or 0)
    paths = []
    for p in t.iter('path'):
        d = p.get(A + 'pathData')
        rule = 'evenodd' if p.get(A + 'fillType') == 'evenOdd' else ''
        paths.append([d, rule] if rule else d)
    vb = f"0 {-int(ty) if ty else 0} {vw.rstrip('0').rstrip('.') if '.' in vw else vw} {vh.rstrip('0').rstrip('.') if '.' in vh else vh}"
    out[name] = [vb, paths]
dst = os.path.join(os.path.dirname(__file__), '..', 'src', 'icons', 'paths.ts')
with open(dst, 'w') as fh:
    fh.write('// তৈরি করা ফাইল — web/scripts/icons.py (অ্যাপের আইকন থেকে)। হাতে বদলাবেন না।\n')
    fh.write('export const ICONS: Record<string, [string, (string | [string, string])[]]> = ')
    fh.write(json.dumps(out, ensure_ascii=False, separators=(',', ':')))
    fh.write(';\n')
print(len(out), 'icons')
