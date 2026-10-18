# ছবি + ধাপের তালিকা → manifest.json (ছবি থাকলে "pass", না থাকলে "fail"; পরে চোখে দেখে ঠিক করা হয়)
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from steps import JOURNEYS, STEPS

shots, out = sys.argv[1], sys.argv[2]
have = {os.path.splitext(f)[0]: f for f in os.listdir(shots) if f.endswith(".png")}
rows = []
for key, title, expect in STEPS:
    j = key.split("-")[0]
    if key.startswith("j1-00") is False and "-00" in key:
        continue
    jt, role = JOURNEYS[j]
    rows.append({
        "journey": j, "journeyTitleBn": jt, "step": key.split("-")[1], "titleBn": title, "role": role,
        "file": have.get(key, key + ".png"), "expectedBn": expect,
        "status": "pass" if key in have else "fail",
        "noteBn": "" if key in have else "এই ধাপে পৌঁছানো যায়নি (আগের ধাপে আটকে গেছে)",
    })
json.dump(rows, open(out, "w"), ensure_ascii=False, indent=1)
print(f"{sum(r['status']=='pass' for r in rows)}/{len(rows)} steps have screenshots")
