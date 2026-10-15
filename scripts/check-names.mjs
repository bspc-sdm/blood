// সহজ উচ্চারণের এক শব্দের নামের .com খালি আছে কি না (Verisign RDAP: 404 = খালি)।
import { writeFileSync } from 'node:fs';
const roots = ['care', 'cara', 'heal', 'hela', 'life', 'lifa', 'puls', 'aida', 'aido', 'medo', 'vida', 'vivo', 'kina', 'savi', 'nuro', 'ama', 'ela', 'oma', 'kura', 'lyfe', 'helth', 'curo', 'medi', 'vita', 'seva'];
const ends = ['lo', 'la', 'ly', 'vo', 'va', 'ya', 'zo', 'ra', 'na', 'ko', 'go', 'mi', 'ni', 'ti', 'ri', 'o', 'a', 'io', 'ix', 'ify', 'ora', 'ova', 'ena', 'ero'];
const extra = [];
const names = new Set(extra);
for (const r of roots) for (const e of ends) { const n = r + e; if (n.length >= 4 && n.length <= 7) names.add(n); }
const out = [];
for (const n of names) {
  let status = 'err';
  for (let i = 0; i < 3; i++) {
    try {
      const r = await fetch(`https://rdap.verisign.com/com/v1/domain/${n}.com`);
      status = r.status === 404 ? 'free' : r.status === 200 ? 'taken' : `http${r.status}`;
      if (status === 'free' || status === 'taken') break;
    } catch (e) { status = 'err'; }
    await new Promise((res) => setTimeout(res, 1500));
  }
  out.push(`${n}.com ${status}`);
  await new Promise((res) => setTimeout(res, 250));
}
writeFileSync('names.txt', out.join('\n') + '\n');
console.log(out.filter((x) => x.endsWith('free')).join('\n'));
