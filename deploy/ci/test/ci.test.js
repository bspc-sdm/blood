// deploy/ci-এর সাহায্যকারী (Setup VPS workflow): চাবি বানানো, Vercel-এর মান বাছাই, env ফাইল লেখা।
//   node --test deploy/ci/test/
const test = require('node:test');
const assert = require('assert');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const K = require('../derive-keys');
const E = require('../server-env');

const SA = {
  type: 'service_account',
  project_id: 'blood-3eb28',
  private_key: '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n',
  client_email: 'x@blood-3eb28.iam.gserviceaccount.com',
};

test('চাবি: একই গোপন মান → একই চাবি; আলাদা মান/লেবেল → আলাদা; পেস্টের \\r\\n আর ফাঁকা উপেক্ষা', () => {
  const a = K.derive('Pass-123');
  const b = K.derive('  Pass-123\r\n');
  assert.strictEqual(a.host.publicKey, b.host.publicKey);
  assert.strictEqual(a.root.privateKey, b.root.privateKey);
  assert.notStrictEqual(a.host.publicKey.split(' ')[1], a.root.publicKey.split(' ')[1]);
  assert.notStrictEqual(K.derive('Pass-124').root.publicKey, a.root.publicKey);
  assert.throws(() => K.derive('  '));
  const keyText = '-----BEGIN OPENSSH PRIVATE KEY-----\r\nabc\r\n-----END OPENSSH PRIVATE KEY-----\r\n';
  assert.strictEqual(K.derive(keyText).host.publicKey, K.derive(keyText.replace(/\r/g, '')).host.publicKey);
});

test('চাবি: OpenSSH-এর ফাইল হিসেবে চলে, আর সই যাচাই হয় (ssh-keygen থাকলে)', (t) => {
  const k = K.derive('Pass-123').root;
  assert.match(k.privateKey, /^-----BEGIN OPENSSH PRIVATE KEY-----\n[\s\S]+\n-----END OPENSSH PRIVATE KEY-----\n$/);
  assert.match(k.publicKey, /^ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI[A-Za-z0-9+/=]+ lifolo-github-root$/);
  try {
    execFileSync('ssh-keygen', ['-?'], { stdio: 'ignore' });
  } catch (e) {
    if (e.code === 'ENOENT') return t.skip('ssh-keygen নেই');
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-key-'));
  const f = path.join(dir, 'k');
  fs.writeFileSync(f, k.privateKey, { mode: 0o600 });
  assert.strictEqual(execFileSync('ssh-keygen', ['-y', '-f', f]).toString().trim(), k.publicKey);
  fs.rmSync(dir, { recursive: true });
});

test('চাবি: seed থেকে বানানো ed25519 আসল সই দেয় (Node-এর crypto দিয়ে যাচাই)', () => {
  const seed = crypto.randomBytes(32);
  const k = K.ed25519FromSeed(seed, 'c');
  const pub = Buffer.from(k.publicKey.split(' ')[1], 'base64').subarray(-32);
  const priv = crypto.createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]), format: 'der', type: 'pkcs8' });
  const sig = crypto.sign(null, Buffer.from('hi'), priv);
  const pubKey = crypto.createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: pub.toString('base64url') }, format: 'jwk' });
  assert.ok(crypto.verify(null, Buffer.from('hi'), pubKey, sig));
});

test('env: quote/parse — systemd আর bash দুটোতেই একই মান', () => {
  const tricky = 'https://x.test/send?k=a&b=$HOME `x` "q" \\ end\'s';
  const text = E.setLines('PORT=3000\nSMS_URL_TEMPLATE=\n#DATABASE_URL=postgresql://a\n', { SMS_URL_TEMPLATE: tricky, GMAIL_USER: 'a@b.c' });
  assert.match(text, /^PORT=3000\nSMS_URL_TEMPLATE="[^\n]+"\n#DATABASE_URL=postgresql:\/\/a\nGMAIL_USER="a@b.c"\n$/);
  assert.strictEqual(E.parseEnv(text).SMS_URL_TEMPLATE, tricky);
  // bash নিজে পড়লে একই মান
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lifolo-env-'));
  fs.writeFileSync(path.join(dir, 'env'), text);
  const out = execFileSync('bash', ['-c', 'set -a; . "$1"; printf %s "$SMS_URL_TEMPLATE"', '_', path.join(dir, 'env')]).toString();
  assert.strictEqual(out, tricky);
  fs.rmSync(dir, { recursive: true });
  assert.throws(() => E.quote('a\nb'));
  // একই চাবি দুবার থাকলে একটাই থাকে
  assert.strictEqual(E.setLines('A=1\nGMAIL_USER=x\nGMAIL_USER=y\n', { GMAIL_USER: 'z' }), 'A=1\nGMAIL_USER="z"\n');
});

test('Firebase service account: JSON বা base64 → base64 (এক লাইন), ভুল হলে স্পষ্ট ত্রুটি', () => {
  const pretty = JSON.stringify(SA, null, 2);
  const a = E.normalizeFirebase(pretty);
  const b = E.normalizeFirebase(Buffer.from(pretty).toString('base64'));
  assert.strictEqual(a.base64, b.base64);
  assert.strictEqual(a.projectId, 'blood-3eb28');
  assert.ok(!/\s/.test(a.base64));
  assert.deepStrictEqual(JSON.parse(Buffer.from(a.base64, 'base64').toString()), SA);
  assert.throws(() => E.normalizeFirebase('{"type":"user"}'), /service account/);
  assert.throws(() => E.normalizeFirebase('not json'), /FIREBASE_SERVICE_ACCOUNT/);
});

test('মান বাছাই: GitHub secret > Vercel > VPS; Sensitive আর না-থাকা দরকারি মান = ফাঁক', () => {
  const fb = JSON.stringify(SA);
  // সব Vercel থেকে
  let d = E.decide({ vercel: { values: { FIREBASE_SERVICE_ACCOUNT: fb, GMAIL_USER: 'g@x.y', GMAIL_APP_PASSWORD: 'abcd efgh', CRON_SECRET: 'c' }, unreadable: [] } });
  assert.deepStrictEqual(d.gaps, []);
  assert.strictEqual(d.sources.GMAIL_USER, 'vercel');
  assert.strictEqual(d.updates.CRON_SECRET, 'c');
  assert.ok(!('SMS_URL_TEMPLATE' in d.updates));
  // Sensitive: পাসওয়ার্ড আর SMS ফাঁক; CRON_SECRET/WEB_SESSION_SECRET নয় (VPS বানায়)
  d = E.decide({ vercel: { values: { FIREBASE_SERVICE_ACCOUNT: fb, GMAIL_USER: 'g' }, unreadable: ['GMAIL_APP_PASSWORD', 'SMS_URL_TEMPLATE', 'CRON_SECRET', 'WEB_SESSION_SECRET'] } });
  assert.deepStrictEqual(d.gaps.map((g) => g.key), ['GMAIL_APP_PASSWORD', 'SMS_URL_TEMPLATE']);
  assert.ok(d.gaps.every((g) => g.reason === 'sensitive'));
  // GitHub secret দিয়ে পূরণ; GitHub secret Vercel-এর চেয়ে আগে
  d = E.decide({
    vercel: { values: { FIREBASE_SERVICE_ACCOUNT: fb, GMAIL_USER: 'g' }, unreadable: ['GMAIL_APP_PASSWORD', 'SMS_URL_TEMPLATE'] },
    secrets: { GMAIL_APP_PASSWORD: 'p', SMS_URL_TEMPLATE: 's', GMAIL_USER: 'gh@x.y' },
  });
  assert.deepStrictEqual(d.gaps, []);
  assert.strictEqual(d.updates.GMAIL_USER, 'gh@x.y');
  assert.strictEqual(d.sources.GMAIL_USER, 'github');
  // আবার চালানো: VPS-এ আগে বসানো মান থাকলে Vercel ছাড়াও চলে
  d = E.decide({ vercel: {}, current: { FIREBASE_SERVICE_ACCOUNT: 'x', GMAIL_USER: 'g', GMAIL_APP_PASSWORD: 'p' } });
  assert.deepStrictEqual(d.gaps, []);
  assert.deepStrictEqual(d.updates, {});
  // টোকেন নেই, VPS খালি → দরকারি তিনটা ফাঁক
  d = E.decide({ vercel: {} });
  assert.deepStrictEqual(d.gaps.map((g) => g.key), ['FIREBASE_SERVICE_ACCOUNT', 'GMAIL_USER', 'GMAIL_APP_PASSWORD']);
  // বার্তায় প্রতিটা নাম আর কোথায় পাবেন
  const msg = E.gapMessage(d.gaps, false);
  for (const k of ['FIREBASE_SERVICE_ACCOUNT', 'GMAIL_USER', 'GMAIL_APP_PASSWORD']) assert.ok(msg.includes(`\`${k}\``));
  assert.match(msg, /Firebase Console/);
});

test('Vercel-এর উত্তর: Sensitive বা না-খোলা মান পড়া যায় না', () => {
  assert.ok(E.readable({ type: 'encrypted', decrypted: true, value: 'v' }));
  assert.ok(E.readable({ type: 'plain', value: 'v' }));
  assert.ok(!E.readable({ type: 'sensitive', decrypted: false, value: '' }));
  assert.ok(!E.readable({ type: 'sensitive', value: 'v' }));
  assert.ok(!E.readable({ type: 'encrypted', decrypted: false, value: 'cipher' }));
  assert.ok(!E.readable({ type: 'encrypted', decrypted: true }));
});

test('Vercel: alias থেকে প্রজেক্ট, তারপর production-এর মান (নকল API)', async () => {
  const calls = [];
  const realFetch = global.fetch;
  global.fetch = async (u) => {
    const url = new URL(u);
    calls.push(url.pathname + url.search);
    const json = (status, body) => ({ status, json: async () => body });
    if (url.pathname === '/v2/teams') return json(200, { teams: [{ id: 'team_1' }] });
    if (url.pathname.startsWith('/v4/aliases/')) return url.searchParams.get('teamId') === 'team_1' ? json(200, { projectId: 'prj_1' }) : json(404, {});
    if (url.pathname === '/v10/projects/prj_1/env') {
      return json(200, { envs: [
        { id: 'e1', key: 'GMAIL_USER', target: ['production', 'preview'] },
        { id: 'e2', key: 'GMAIL_APP_PASSWORD', target: ['production'] },
        { id: 'e3', key: 'GMAIL_USER', target: ['preview'], gitBranch: 'x' },
        { id: 'e4', key: 'OTHER', target: ['production'] },
      ] });
    }
    if (url.pathname === '/v1/projects/prj_1/env/e1') return json(200, { type: 'encrypted', decrypted: true, value: 'g@x.y', key: 'GMAIL_USER' });
    if (url.pathname === '/v1/projects/prj_1/env/e2') return json(200, { type: 'sensitive', decrypted: false, key: 'GMAIL_APP_PASSWORD' });
    return json(404, {});
  };
  try {
    const r = await E.fetchVercel('tok');
    assert.strictEqual(r.projectId, 'prj_1');
    assert.strictEqual(r.teamId, 'team_1');
    assert.deepStrictEqual(r.values, { GMAIL_USER: 'g@x.y' });
    assert.deepStrictEqual(r.unreadable, ['GMAIL_APP_PASSWORD']);
    assert.ok(r.absent.includes('FIREBASE_SERVICE_ACCOUNT'));
    assert.deepStrictEqual(r.other, ['OTHER']);
    assert.ok(calls.every((c) => !c.includes('tok')));
  } finally {
    global.fetch = realFetch;
  }
});

test('লগে লুকানো: পুরো মান আর বহু-লাইনের প্রতিটা লাইন', () => {
  assert.deepStrictEqual(E.maskLines('abc'), []);
  assert.deepStrictEqual(E.maskLines('secret-1'), ['secret-1']);
  const m = E.maskLines('{\n  "private_key": "AAAA"\n}');
  assert.ok(m.includes('"private_key": "AAAA"'));
});
