# drugs-raw.json (scripts/fetch-drugs.mjs) → app/src/main/assets/drugs.json
# পশুর ওষুধ (DAR নম্বরের শেষে 077, বোলাস ইত্যাদি) বাদ; একই ব্র্যান্ড+জেনেরিক+ধরন একবার।
# চালানো: python3 -I scripts/build-drug-list.py path/to/drugs-raw.json
import json, re, sys, collections
raw = json.load(open(sys.argv[1]))
VET_FORMS = {'Bolus', 'Water Soluble Powder', 'Premix', 'Feed Premix', 'Oral Liquid (Vet)'}
companies, forms = [], []
ci, fi = {}, {}
def idx(v, lst, m):
    if v not in m:
        m[v] = len(lst); lst.append(v)
    return m[v]
seen = set(); rows = []
skipped = collections.Counter()
for x in raw:
    if x['cls'] != 'Drug' or x.get('retired'): continue
    e = x['extras']
    name = (e.get('trade_name') or x['name'] or '').strip()
    gen = re.sub(r'\s+', ' ', (e.get('generic_content_raw') or '')).strip()
    form = (e.get('dosage_form') or '').strip()
    comp = re.sub(r'\s+', ' ', (e.get('company') or '')).strip()
    dar = e.get('dar_number') or ''
    if dar.endswith('-077') or form in VET_FORMS or re.search(r'\bvet\b', name, re.I):
        skipped['vet'] += 1; continue
    if not name: continue
    key = (name.lower(), gen.lower(), form.lower())
    if key in seen: skipped['dup'] += 1; continue
    seen.add(key)
    rows.append([name, gen, idx(form, forms, fi), idx(comp, companies, ci)])
rows.sort(key=lambda r: r[0].lower())
out = {'source': 'DGDA registered drugs via DGHS terminology server (api.tr.ocl.dghs.gov.bd)', 'f': forms, 'c': companies, 'd': rows}
s = json.dumps(out, ensure_ascii=False, separators=(',', ':'))
open('app/src/main/assets/drugs.json', 'w').write(s)
print(len(rows), 'drugs', len(forms), 'forms', len(companies), 'companies', len(s), 'bytes', dict(skipped))
