// সহজ উচ্চারণের এক শব্দের নামের .com খালি আছে কি না (Verisign RDAP: 404 = খালি)।
import { writeFileSync } from 'node:fs';
const roots = ['caro', 'cura', 'medi', 'vita', 'sevo', 'jivo', 'helo', 'tendo', 'sano', 'kuro', 'lifo', 'amo', 'nira', 'suvo', 'pulso', 'medo', 'karo', 'sena', 'vela', 'nomo', 'rivo', 'tura', 'kemi', 'dovo', 'mira'];
const ends = ['', 'la', 'va', 'ra', 'na', 'ko', 'lo', 'vi', 'ya', 'ly', 'go', 'zo', 'ni', 'mo', 'ti'];
const extra = ['carevo', 'caremi', 'curaly', 'medvia', 'vitago', 'sevoka', 'jivoka', 'helvio', 'tendia', 'kurova', 'sanova', 'lifeso', 'amorio', 'nirova', 'suvola', 'medino', 'vitavo', 'carezo', 'jivana', 'pasho', 'shebo', 'arova', 'medlo', 'kindo', 'helpo', 'kairo', 'nuvo', 'zeno', 'luma', 'sobo', 'dakto', 'docko', 'medko', 'vaido', 'shastho', 'ranvo', 'tolu', 'kemo', 'bimo', 'jeevo'];
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
