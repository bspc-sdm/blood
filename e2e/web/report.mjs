// e2e-র ফল এক জায়গায় (CI-র লগ branch-এর জন্য): node e2e/web/report.mjs <label> <out-dir> [backend-log]
//   <out-dir>/report-<label>.json — যাত্রা/দৃশ্য/ধাপের হিসাব, ব্যর্থ দৃশ্য, API-র ৪xx/৫xx, সার্ভারের ERROR লাইন
//   <out-dir>/report-<label>.md   — পড়ার মতো সারাংশ
//   <out-dir>/shots-<label>/      — ভুল ('bug') ধাপের স্ক্রিনশট (৪০টা পর্যন্ত)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const SHOTS = path.join(here, 'shots');
const E2E = path.join(here, '..');
const [label = 'run', outDir = path.join(E2E, '.build/report'), backendLog = ''] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });

const read = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };
const summary = read(path.join(SHOTS, 'summary.json'), []);
const manifest = read(path.join(SHOTS, 'manifest.json'), []);
const issues = read(path.join(SHOTS, 'issues.json'), []);

const journeys = summary.map((j) => {
  const steps = manifest.filter((m) => m.journey === j.journey);
  const sc = j.scenarios || [];
  return {
    journey: j.journey,
    steps: steps.length,
    bugSteps: steps.filter((m) => m.status === 'bug').map((m) => ({ file: m.file, title: m.titleBn, note: m.noteBn, scenario: m.scenario })),
    scenarios: sc.length,
    scenariosOk: sc.filter((s) => s.ok).length,
    failedScenarios: sc.filter((s) => !s.ok),
    stopped: j.failed,
    seconds: j.s,
    known: j.known || [],
  };
});

// API-র ভুল: stack.log-এ "API <status> <path> <action> <error>" (E2E_VERBOSE)
const apiErrors = {};
const stackLog = path.join(E2E, '.build/stack.log');
if (fs.existsSync(stackLog)) {
  for (const line of fs.readFileSync(stackLog, 'utf8').split('\n')) {
    const m = /^API (\d{3}) (\S+) (\S+) ?(.*)$/.exec(line);
    if (!m) continue;
    const k = `${m[1]} ${m[2]} ${m[3]} ${m[4]}`.trim().slice(0, 240);
    apiErrors[k] = (apiErrors[k] || 0) + 1;
  }
}

// সার্ভারের ERROR (Spring-এর লগ): প্রথম লাইন + stack trace-এর শুরু
const serverErrors = [];
if (backendLog && fs.existsSync(backendLog)) {
  const lines = fs.readFileSync(backendLog, 'utf8').split('\n');
  for (let i = 0; i < lines.length && serverErrors.length < 80; i++) {
    if (/\bERROR\b/.test(lines[i]) || /^\S*Exception|^Caused by/.test(lines[i])) {
      const block = [lines[i]];
      for (let k = i + 1; k < lines.length && k < i + 12 && /^(\s+at |\s*Caused by|\s+\.\.\.|[\w.$]+(Exception|Error)\b)/.test(lines[k]); k++) block.push(lines[k]);
      serverErrors.push(block.join('\n').slice(0, 2500));
      i += block.length - 1;
    }
  }
}

const tot = (k) => journeys.reduce((a, j) => a + (typeof j[k] === 'number' ? j[k] : j[k].length), 0);
const report = {
  label,
  journeys,
  totals: {
    journeys: journeys.length,
    stopped: journeys.filter((j) => j.stopped).length,
    scenarios: tot('scenarios'),
    scenariosOk: tot('scenariosOk'),
    scenariosFailed: tot('failedScenarios'),
    steps: tot('steps'),
    bugSteps: tot('bugSteps'),
    autoIssues: issues.length,
    api5xx: Object.entries(apiErrors).filter(([k]) => k.startsWith('5')).reduce((a, [, n]) => a + n, 0),
    serverErrors: serverErrors.length,
    knownBugs: journeys.reduce((a, j) => a + j.known.filter((k) => !k.passed).length, 0),
  },
  apiErrors,
  serverErrors,
  issues: issues.slice(0, 60),
};
report.green = report.totals.journeys > 0 && !report.totals.stopped && !report.totals.scenariosFailed && !report.totals.bugSteps && !report.totals.api5xx;
fs.writeFileSync(path.join(outDir, `report-${label}.json`), JSON.stringify(report, null, 1));

// ভুল ধাপের ছবি
const shotDir = path.join(outDir, `shots-${label}`);
fs.mkdirSync(shotDir, { recursive: true });
let copied = 0;
for (const j of journeys) {
  for (const b of j.bugSteps) {
    if (copied >= 40) break;
    const src = path.join(SHOTS, b.file);
    if (fs.existsSync(src)) { fs.copyFileSync(src, path.join(shotDir, b.file.replace(/\//g, '__'))); copied++; }
  }
}

const t = report.totals;
const md = [];
md.push(`# Web e2e — ${label} ${report.green ? '✅' : '❌'}`);
md.push('');
md.push(`যাত্রা ${t.journeys} (থেমেছে ${t.stopped}) · দৃশ্য ${t.scenariosOk}/${t.scenarios} পাস (${t.scenariosFailed} ব্যর্থ) · ধাপ ${t.steps} (ভুল ${t.bugSteps}) · API 5xx ${t.api5xx} · সার্ভার ERROR ${t.serverErrors} · নিজে ধরা সন্দেহ ${t.autoIssues} · জানা ভুল ${t.knownBugs}`);
md.push('');
md.push('| যাত্রা | দৃশ্য পাস | ধাপ | ভুল ধাপ | থেমেছে | সময় |');
md.push('|---|---|---|---|---|---|');
for (const j of journeys) md.push(`| ${j.journey} | ${j.scenariosOk}/${j.scenarios} | ${j.steps} | ${j.bugSteps.length} | ${j.stopped ? j.stopped.slice(0, 80).replace(/\|/g, '/') : '—'} | ${j.seconds}s |`);
const failed = journeys.flatMap((j) => j.failedScenarios.map((s) => `- **${j.journey} ${s.id}**: ${s.msg || ''}`));
if (failed.length) md.push('', '## ব্যর্থ দৃশ্য', '', ...failed);
const bugs = journeys.flatMap((j) => j.bugSteps.map((b) => `- ${b.file} — ${b.title}${b.note ? ` — ${b.note}` : ''}`));
if (bugs.length) md.push('', '## ভুল ধাপ (status: bug)', '', ...bugs);
const known = journeys.flatMap((j) => j.known.map((k) => `- ${k.passed ? '✅ এখন পাস করে (xfail সরান)' : '⚠️'} **${k.bug}** (${j.journey}) ${k.title}${k.msg ? ` — ${k.msg}` : ''}`));
if (known.length) md.push('', '## জানা ভুল (অ্যাপ/সার্ভারের, রিপোর্ট করা — রান লাল করে না)', '', ...known);
const api = Object.entries(apiErrors).sort((a, b) => b[1] - a[1]);
if (api.length) md.push('', '## API-র ৪xx/৫xx (সংখ্যা)', '', ...api.slice(0, 80).map(([k, n]) => `- ${n}× ${k}`));
if (serverErrors.length) md.push('', '## সার্ভারের ERROR', '', ...serverErrors.slice(0, 40).map((e) => '```\n' + e + '\n```'));
fs.writeFileSync(path.join(outDir, `report-${label}.md`), md.join('\n') + '\n');
console.log(md.slice(0, 3).join('\n'));
