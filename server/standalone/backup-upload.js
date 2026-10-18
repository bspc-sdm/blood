#!/usr/bin/env node
// PostgreSQL ব্যাকআপ VPS-এর বাইরে: Firebase প্রজেক্টের Cloud Storage বাকেটে, backups/ ফোল্ডারে, শেষ ১৪টা রাখা।
// lifolo-backup.service সফল হলে lifolo-backup-upload.service এটা চালায় (lifolo ব্যবহারকারী, /etc/lifolo/env)।
//
//   node standalone/backup-upload.js upload            সবচেয়ে নতুন /var/backups/lifolo/lifolo-*.dump তোলা, পুরোনো মোছা
//   node standalone/backup-upload.js list              বাকেটে কোন কোন ব্যাকআপ আছে
//   node standalone/backup-upload.js download <নাম|latest> <ফাইল>   নামানো (ফেরত আনার জন্য)
//   node standalone/backup-upload.js check             বাকেট পাওয়া যায় কি না (Setup VPS দেখে)
//
// পরিবেশ: FIREBASE_SERVICE_ACCOUNT (লাগবেই), BACKUP_BUCKET (না দিলে <project>.firebasestorage.app, তারপর <project>.appspot.com),
//         BACKUP_DIR (/var/backups/lifolo), BACKUP_KEEP (১৪)
'use strict';
const fs = require('fs');
const path = require('path');
const { loadServiceAccount, accessToken } = require('./gtoken');

const GCS = 'https://storage.googleapis.com';
const PREFIX = 'backups/';
const NAME_RE = /^lifolo-\d{8}-\d{6}\.dump$/;

function bucketCandidates(projectId, env = process.env) {
  if (env.BACKUP_BUCKET) return [env.BACKUP_BUCKET.replace(/^gs:\/\//, '').replace(/\/+$/, '')];
  return [`${projectId}.firebasestorage.app`, `${projectId}.appspot.com`];
}

/** বাকেটের নামগুলো থেকে কোনগুলো মুছতে হবে (নাম = সময়, তাই নামে সাজালেই পুরোনো আগে) */
function toPrune(objectNames, keep) {
  const ours = objectNames.filter((n) => n.startsWith(PREFIX) && NAME_RE.test(n.slice(PREFIX.length))).sort();
  return ours.slice(0, Math.max(0, ours.length - keep));
}

function newestLocal(dir) {
  const files = fs.readdirSync(dir).filter((f) => NAME_RE.test(f)).sort();
  return files.length ? path.join(dir, files[files.length - 1]) : null;
}

class Bucket {
  constructor(token, name, fetchImpl = fetch) {
    this.token = token;
    this.name = name;
    this.fetch = fetchImpl;
  }

  async req(method, url, { body, headers = {}, ok = [200] } = {}) {
    const res = await this.fetch(url, { method, body, headers: { Authorization: `Bearer ${this.token}`, ...headers } });
    if (!ok.includes(res.status)) {
      const text = await res.text().catch(() => '');
      const err = new Error(`${method} ${url.replace(GCS, '')} → HTTP ${res.status} ${text.slice(0, 300)}`);
      err.status = res.status;
      throw err;
    }
    return res;
  }

  async exists() {
    const res = await this.req('GET', `${GCS}/storage/v1/b/${encodeURIComponent(this.name)}?fields=name`, { ok: [200, 404] });
    return res.status === 200;
  }

  async list() {
    const names = [];
    let pageToken = '';
    do {
      const u = new URL(`${GCS}/storage/v1/b/${encodeURIComponent(this.name)}/o`);
      u.searchParams.set('prefix', PREFIX);
      u.searchParams.set('fields', 'items(name,size,updated),nextPageToken');
      if (pageToken) u.searchParams.set('pageToken', pageToken);
      const body = await (await this.req('GET', u.toString())).json();
      for (const it of body.items || []) names.push(it);
      pageToken = body.nextPageToken || '';
    } while (pageToken);
    return names;
  }

  async upload(file, objectName) {
    const u = new URL(`${GCS}/upload/storage/v1/b/${encodeURIComponent(this.name)}/o`);
    u.searchParams.set('uploadType', 'media');
    u.searchParams.set('name', objectName);
    // ব্যাকআপ ছোট (কয়েক MB) — পুরোটা মেমোরিতে নিয়ে একবারে
    const data = fs.readFileSync(file);
    await this.req('POST', u.toString(), { body: data, headers: { 'Content-Type': 'application/octet-stream' } });
    return data.length;
  }

  async remove(objectName) {
    await this.req('DELETE', `${GCS}/storage/v1/b/${encodeURIComponent(this.name)}/o/${encodeURIComponent(objectName)}`, { ok: [204, 404] });
  }

  async download(objectName, dest) {
    const res = await this.req('GET', `${GCS}/storage/v1/b/${encodeURIComponent(this.name)}/o/${encodeURIComponent(objectName)}?alt=media`);
    const tmp = `${dest}.partial`;
    fs.writeFileSync(tmp, Buffer.from(await res.arrayBuffer()), { mode: 0o640 });
    fs.renameSync(tmp, dest);
  }
}

async function openBucket(env = process.env, fetchImpl = fetch) {
  const sa = loadServiceAccount(env.FIREBASE_SERVICE_ACCOUNT);
  const token = await accessToken(sa, undefined, fetchImpl);
  const tried = [];
  for (const name of bucketCandidates(sa.project_id, env)) {
    const b = new Bucket(token, name, fetchImpl);
    try {
      if (await b.exists()) return b;
      tried.push(`${name} (নেই)`);
    } catch (e) {
      tried.push(`${name} (${e.status === 403 ? 'অনুমতি নেই' : e.message})`);
    }
  }
  const err = new Error(`Cloud Storage বাকেট পাওয়া যায়নি: ${tried.join(', ')}. Firebase Console → Storage → Get started চাপুন (বা BACKUP_BUCKET দিন)।`);
  err.code = 'NO_BUCKET';
  throw err;
}

async function main(argv = process.argv.slice(2), env = process.env) {
  const [cmd, ...args] = argv;
  const keep = Math.max(1, Number(env.BACKUP_KEEP) || 14);
  const dir = env.BACKUP_DIR || '/var/backups/lifolo';
  if (!['upload', 'list', 'download', 'check'].includes(cmd)) {
    console.error('usage: backup-upload.js upload | list | download <name|latest> <file> | check');
    return 2;
  }
  const bucket = await openBucket(env);
  if (cmd === 'check') {
    console.log(`bucket ok: gs://${bucket.name}/${PREFIX}`);
    return 0;
  }
  if (cmd === 'list') {
    const items = (await bucket.list()).filter((it) => NAME_RE.test(it.name.slice(PREFIX.length))).sort((a, b) => a.name.localeCompare(b.name));
    for (const it of items) console.log(`${it.name.slice(PREFIX.length)}\t${it.size}\t${it.updated}`);
    if (!items.length) console.log('(কোনো ব্যাকআপ নেই)');
    return 0;
  }
  if (cmd === 'download') {
    let [name, dest] = args;
    if (!name || !dest) throw new Error('download <name|latest> <file>');
    if (name === 'latest') {
      const all = (await bucket.list()).map((it) => it.name.slice(PREFIX.length)).filter((n) => NAME_RE.test(n)).sort();
      if (!all.length) throw new Error('বাকেটে কোনো ব্যাকআপ নেই');
      name = all[all.length - 1];
    }
    if (!NAME_RE.test(name)) throw new Error(`ব্যাকআপের নাম ঠিক নেই: ${name}`);
    await bucket.download(PREFIX + name, dest);
    console.log(`downloaded ${name} → ${dest}`);
    return 0;
  }
  // upload
  const file = newestLocal(dir);
  if (!file) throw new Error(`${dir}-এ কোনো ব্যাকআপ নেই`);
  const objectName = PREFIX + path.basename(file);
  const size = await bucket.upload(file, objectName);
  console.log(`uploaded gs://${bucket.name}/${objectName} (${size} bytes)`);
  const names = (await bucket.list()).map((it) => it.name);
  for (const old of toPrune(names, keep)) {
    await bucket.remove(old);
    console.log(`deleted old gs://${bucket.name}/${old}`);
  }
  return 0;
}

module.exports = { bucketCandidates, toPrune, newestLocal, Bucket, openBucket, main, PREFIX };

if (require.main === module) {
  main().then((code) => { process.exitCode = code; }, (e) => {
    // fetch-এর "fetch failed"-এর আসল কারণ (DNS, সংযোগ) cause-এ থাকে
    console.error(e.message + (e.cause ? ` (${e.cause.code || e.cause.message})` : ''));
    process.exitCode = 1;
  });
}
