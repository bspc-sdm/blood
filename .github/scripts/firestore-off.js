#!/usr/bin/env node
// Firestore বন্ধ করার এককালীন কাজ (.github/workflows/firestore-off.yml থেকে চলে)। firebase-admin দিয়ে:
//   export <out.json.gz> <manifest.json>  — (default) ডাটাবেসের সব collection আর subcollection (পুনরাবৃত্তিভাবে) JSON-এ, gzip
//   rules-get <out.rules> <info.json>     — এখনকার Firestore rules-এর লেখা (ফেরত আনার জন্য)
//   rules-lock <info.json>                — rules = সব পথে deny-all, তারপর আবার পড়ে মিলিয়ে দেখা
//   storage <info.json>                   — ডিফল্ট Storage বাকেটে ফাইল আছে কি না (শুধু গোনা; কিছু বদলায় না)
// SA_FILE = service account JSON-এর পথ। stdout/লগে কোনো ডকুমেন্টের তথ্য, ID বা ইমেইল যায় না — শুধু collection-এর নাম আর সংখ্যা।
'use strict';
const fs = require('fs');
const zlib = require('zlib');
const crypto = require('crypto');

// firebase-admin workflow-এ আলাদা ফোল্ডারে বসানো; NODE_PATH=<সেই ফোল্ডার>/node_modules দিয়ে চালানো হয়
const admin = require('firebase-admin');
const { getFirestore, Timestamp, GeoPoint, DocumentReference } = require('firebase-admin/firestore');

const DENY_ALL = `rules_version = '2';

// Lifolo: Firestore বন্ধ (deny-all)। অ্যাপ আর ওয়েব VPS-এর Spring সার্ভার (PostgreSQL) ব্যবহার করে।
// আগের rules VPS-এ /var/backups/lifolo/-এ রাখা — ফেরত আনার নিয়ম deploy/CUTOVER.md-এ।
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
`;

const sa = JSON.parse(fs.readFileSync(process.env.SA_FILE, 'utf8'));
const PROJECT = sa.project_id;
const app = admin.initializeApp({ credential: admin.credential.cert(sa), projectId: PROJECT });

// ত্রুটির বার্তায় ইমেইল (service account-এর নামও) না রাখা
const clean = (e) => String((e && (e.message || e)) || '').replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/g, '<email>').slice(0, 300);
const isPerm = (e) => /PERMISSION_DENIED|permission|403|does not have|forbidden/i.test(String((e && e.message) || '') + ' ' + String((e && e.code) || ''));
const permName = (e) => ((String((e && e.message) || '').match(/firebaserules\.[a-zA-Z.]+|datastore\.[a-zA-Z.]+|storage\.[a-zA-Z.]+/) || [])[0] || '');
const writeJson = (f, o) => fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n', { mode: 0o600 });

// ---------------------------------------------------------------- export
function enc(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') {
    return v >= BigInt(Number.MIN_SAFE_INTEGER) && v <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(v) : { __type: 'int64', value: v.toString() };
  }
  if (typeof v === 'number') return Number.isFinite(v) ? v : { __type: 'double', value: String(v) };
  if (typeof v === 'string' || typeof v === 'boolean') return v;
  if (v instanceof Timestamp) return { __type: 'timestamp', value: v.toDate().toISOString(), seconds: v.seconds, nanoseconds: v.nanoseconds };
  if (v instanceof GeoPoint) return { __type: 'geopoint', latitude: v.latitude, longitude: v.longitude };
  if (v instanceof DocumentReference) return { __type: 'reference', path: v.path };
  if (Buffer.isBuffer(v) || v instanceof Uint8Array) return { __type: 'bytes', base64: Buffer.from(v).toString('base64') };
  if (v && typeof v.toArray === 'function' && v.constructor && /Vector/.test(v.constructor.name)) return { __type: 'vector', values: v.toArray() };
  if (Array.isArray(v)) return v.map(enc);
  if (typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) o[k] = enc(v[k]);
    return o;
  }
  return { __type: 'unknown', value: String(v) };
}

// collection-এর পথ থেকে নমুনা: requests/abc/private → requests/*/private (ID লগে যায় না)
const pattern = (colPath) => colPath.split('/').map((s, i) => (i % 2 ? '*' : s)).join('/');

async function listDatabases() {
  try {
    const { access_token: tok } = await app.options.credential.getAccessToken();
    const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases`, { headers: { authorization: `Bearer ${tok}` } });
    if (!r.ok) return { error: `HTTP ${r.status}` };
    const j = await r.json();
    return { names: (j.databases || []).map((d) => d.name.split('/').pop()) };
  } catch (e) {
    return { error: clean(e) };
  }
}

async function doExport(out, manifestFile) {
  const db = getFirestore(app);
  db.settings({ useBigInt: true });
  const gz = zlib.createGzip({ level: 9 });
  const fileStream = fs.createWriteStream(out, { mode: 0o600 });
  gz.pipe(fileStream);
  const done = new Promise((res, rej) => { fileStream.on('finish', res); fileStream.on('error', rej); gz.on('error', rej); });
  const write = async (s) => { if (!gz.write(s)) await new Promise((r) => gz.once('drain', r)); };

  const groups = {}; // pattern → নথির সংখ্যা
  let total = 0;
  let missingParents = 0;
  let first = true;
  const exportedAt = new Date().toISOString();
  const dbs = await listDatabases();
  await write(`{"format":"lifolo-firestore-export/1","project":${JSON.stringify(PROJECT)},"database":"(default)","exportedAt":"${exportedAt}","documents":[\n`);

  async function walk(col) {
    const pat = pattern(col.path);
    if (!(pat in groups)) groups[pat] = 0;
    const refs = await col.listDocuments(); // ফাঁকা "পূর্বপুরুষ" নথিও (যার শুধু subcollection আছে)
    for (let i = 0; i < refs.length; i += 200) {
      const chunk = refs.slice(i, i + 200);
      const snaps = await db.getAll(...chunk);
      for (const s of snaps) {
        if (s.exists) {
          const rec = {
            path: s.ref.path,
            createTime: s.createTime ? s.createTime.toDate().toISOString() : null,
            updateTime: s.updateTime ? s.updateTime.toDate().toISOString() : null,
            data: enc(s.data()),
          };
          await write((first ? '' : ',\n') + JSON.stringify(rec));
          first = false;
          groups[pat]++;
          total++;
        } else {
          missingParents++;
        }
      }
      for (const ref of chunk) {
        for (const sub of await ref.listCollections()) await walk(sub);
      }
    }
  }

  const top = await db.listCollections();
  for (const c of top.sort((a, b) => a.id.localeCompare(b.id))) await walk(c);
  await write('\n]}\n');
  gz.end();
  await done;

  const buf = fs.readFileSync(out);
  const manifest = {
    project: PROJECT,
    exportedAt,
    databases: dbs,
    total,
    missingParents,
    topLevel: top.map((c) => c.id).sort(),
    groups,
    bytes: buf.length,
    sha256: crypto.createHash('sha256').update(buf).digest('hex'),
  };
  writeJson(manifestFile, manifest);
  console.log(`export: ${total} documents, ${Object.keys(groups).length} collection groups, ${top.length} top-level, ${buf.length} bytes gz`);
}

// ---------------------------------------------------------------- rules
async function rulesGet(outRules, infoFile) {
  const sr = admin.securityRules(app);
  let rs;
  try {
    rs = await sr.getFirestoreRuleset();
  } catch (e) {
    const info = { ok: false, phase: 'get', error: clean(e) };
    if (e && e.code === 'security-rules/not-found') {
      info.none = true;
    } else if (isPerm(e)) {
      info.permission = true;
      info.missing = permName(e) || 'firebaserules.releases.get / firebaserules.rulesets.get';
      info.role = 'Firebase Rules Viewer (roles/firebaserules.viewer) — পড়ার জন্য; বদলের জন্য Firebase Rules Admin (roles/firebaserules.admin)';
    }
    writeJson(infoFile, info);
    console.log(`rules-get failed: ${info.error}`);
    process.exit(2);
  }
  const files = rs.source || [];
  const text = files.length === 1 ? files[0].content : files.map((f) => `// ===== ${f.name} =====\n${f.content}`).join('\n');
  fs.writeFileSync(outRules, text, { mode: 0o600 });
  const sha = crypto.createHash('sha256').update(text).digest('hex');
  const denyAlready = text.trim() === DENY_ALL.trim();
  writeJson(infoFile, { ok: true, ruleset: rs.name, createTime: rs.createTime, files: files.map((f) => f.name), sha256: sha, bytes: Buffer.byteLength(text), denyAlready });
  console.log(`rules-get: ruleset ${rs.name} (${rs.createTime}), ${files.length} file(s), sha256 ${sha.slice(0, 12)}, deny-all already: ${denyAlready}`);
}

async function rulesLock(infoFile) {
  const sr = admin.securityRules(app);
  let rel;
  try {
    rel = await sr.releaseFirestoreRulesetFromSource(DENY_ALL);
  } catch (e) {
    const info = { ok: false, phase: 'release', error: clean(e) };
    if (isPerm(e)) {
      info.permission = true;
      info.missing = permName(e) || 'firebaserules.rulesets.create / firebaserules.releases.update';
      info.role = 'Firebase Rules Admin (roles/firebaserules.admin)';
    }
    writeJson(infoFile, info);
    console.log(`rules-lock failed: ${info.error}`);
    process.exit(2);
  }
  // প্রকাশের পর আবার পড়ে মেলানো
  const now = await sr.getFirestoreRuleset();
  const text = (now.source || []).map((f) => f.content).join('\n');
  const ok = now.name === rel.name && text.trim() === DENY_ALL.trim();
  writeJson(infoFile, { ok, ruleset: now.name, createTime: now.createTime, verified: ok });
  console.log(`rules-lock: released ${rel.name}; read back ${now.name}; deny-all verified: ${ok}`);
  if (!ok) process.exit(3);
}

// ---------------------------------------------------------------- storage (শুধু দেখা)
async function storageInfo(infoFile) {
  const info = { buckets: {} };
  const names = [`${PROJECT}.firebasestorage.app`, `${PROJECT}.appspot.com`];
  for (const name of names) {
    const b = { };
    try {
      const bucket = admin.storage(app).bucket(name);
      const [exists] = await bucket.exists();
      b.exists = exists;
      if (exists) {
        const [files] = await bucket.getFiles({ autoPaginate: true });
        b.objects = files.length;
        b.bytes = files.reduce((n, f) => n + Number((f.metadata && f.metadata.size) || 0), 0);
      }
    } catch (e) {
      b.error = clean(e);
    }
    try {
      await admin.securityRules(app).getStorageRuleset(name);
      b.rules = true;
    } catch (e) {
      b.rules = e && e.code === 'security-rules/not-found' ? false : `unknown (${clean(e).slice(0, 120)})`;
    }
    info.buckets[name] = b;
  }
  writeJson(infoFile, info);
  console.log(`storage: ${JSON.stringify(info.buckets)}`);
}

const [cmd, a, b] = process.argv.slice(2);
const run = { export: () => doExport(a, b), 'rules-get': () => rulesGet(a, b), 'rules-lock': () => rulesLock(a), storage: () => storageInfo(a) }[cmd];
if (!run) { console.error('usage: export|rules-get|rules-lock|storage'); process.exit(64); }
run().then(() => process.exit(0)).catch((e) => { console.error(`${cmd} failed: ${clean(e)}`); process.exit(1); });
