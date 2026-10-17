// শেয়ার করা রিকোয়েস্টের লিংক (/r/{id}): অ্যাপ থাকলে সরাসরি অ্যাপে খোলে (Android App Links),
// না থাকলে এই পাতায় সংক্ষিপ্ত তথ্য আর অ্যাপ নামানোর বাটন দেখায়। রক্তপ্রার্থীর নাম/ফোন/নোট দেখানো হয় না।
// /.well-known/assetlinks.json-ও এখান থেকে দেওয়া হয় (vercel.json-এর rewrite)।
const { db } = require('../lib/firebase');
const R = require('../lib/rules');

const PACKAGE = 'com.bspc.blood';
// অ্যাপ সাইন করার চাবির SHA-256 (app/debug.keystore)। Play Store-এর আলাদা চাবি হলে এখানে যোগ করতে হবে।
const CERT_SHA256 = ['FC:BE:F9:19:39:40:5B:C2:32:72:18:DB:DA:CD:DD:BE:86:47:F6:4B:F9:0A:06:F7:37:AC:42:99:D2:F0:F8:39'];
const APK_URL = 'https://github.com/bspc-sdm/blood/releases/download/latest/roktodan.apk';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function page(title, body) {
  return `<!doctype html><html lang="bn"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<meta property="og:title" content="${esc(title)}">
<style>
:root{--red:#1F6F78;--bg:#F7FAFA;--card:#EBF0F0;--text:#161D1E;--muted:#3F4949}
@media (prefers-color-scheme:dark){:root{--red:#8AD3DB;--bg:#0F1415;--card:#1B2122;--text:#DEE4E4;--muted:#BEC8C9}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,"Noto Sans Bengali",sans-serif}
main{max-width:480px;margin:0 auto;padding:24px 16px}
.logo{display:flex;align-items:center;gap:10px;font-weight:800;font-size:22px;color:var(--red);margin-bottom:24px}
.dot{width:36px;height:36px;border-radius:50%;background:#1F6F78;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px}
.card{background:var(--card);border-radius:20px;padding:20px}
.group{width:72px;height:72px;border-radius:50%;background:#CFEDEF;color:#00363C;font-weight:800;font-size:26px;display:flex;align-items:center;justify-content:center}
.row{display:flex;gap:16px;align-items:center;margin-bottom:16px}
h1{font-size:22px;margin:0}.muted{color:var(--muted);font-size:14px}
dl{margin:0}dt{color:var(--muted);font-size:13px;margin-top:12px}dd{margin:2px 0 0;font-size:17px}
.tag{display:inline-block;background:#FCE4E2;color:#7A2420;border-radius:6px;padding:2px 8px;font-size:12px;font-weight:700;margin-top:6px}
a.btn{display:block;text-align:center;text-decoration:none;border-radius:999px;padding:14px;font-weight:700;margin-top:14px}
.primary{background:#1F6F78;color:#fff}.secondary{border:1px solid var(--muted);color:var(--text)}
</style></head><body><main>
<div class="logo"><div class="dot">+</div>Lifolo</div>
${body}
</main></body></html>`;
}

module.exports = async (req, res) => {
  if (req.query.assetlinks) {
    res.setHeader('Content-Type', 'application/json');
    res.status(200).send(JSON.stringify([{
      relation: ['delegate_permission/common.handle_all_urls'],
      target: { namespace: 'android_app', package_name: PACKAGE, sha256_cert_fingerprints: CERT_SHA256 },
    }]));
    return;
  }

  // প্রেসক্রিপশনের QR (ডাক্তারের ওয়েবে ছাপার জন্য): /api/share?rxqr={code} → SVG
  if (req.query.rxqr) {
    const code = String(req.query.rxqr).toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{10}$/.test(code)) {
      res.status(404).send('');
      return;
    }
    const svg = await require('qrcode').toString(`https://blood-server-ashy.vercel.app/rx/${code}`, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' });
    res.setHeader('Content-Type', 'image/svg+xml');
    res.setHeader('Cache-Control', 'public, max-age=86400');
    res.status(200).send(svg);
    return;
  }

  // ফার্মেসির কাউন্টারের QR: /api/share?shopqr={কোড} → SVG (ভেতরে /shop/{কোড}; অ্যাপ স্ক্যান করে দোকান চেনে)
  if (req.query.shopqr) {
    const code = String(req.query.shopqr).toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{6}$/.test(code)) {
      res.status(404).send('');
      return;
    }
    const svg = await require('qrcode').toString(`https://blood-server-ashy.vercel.app/shop/${code}`, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' });
    res.setHeader('Content-Type', 'image/svg+xml');
    res.setHeader('Cache-Control', 'public, max-age=86400');
    res.status(200).send(svg);
    return;
  }

  // ফোনের সাধারণ ক্যামেরায় দোকানের QR স্ক্যান করলে (/shop/{কোড}): কী করতে হবে
  if (req.query.shop) {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    const code = String(req.query.shop).toUpperCase();
    let name = '';
    if (/^[A-HJ-NP-Z2-9]{6}$/.test(code)) {
      try {
        const snap = await db.collection('pharmacies').where('shopCode', '==', code).limit(1).get();
        if (!snap.empty && snap.docs[0].get('status') === 'approved') name = snap.docs[0].get('name') || '';
      } catch (e) {
        console.error('shop page failed', e);
      }
    }
    if (!name) {
      res.status(404).send(page('Lifolo', '<div class="card"><h1>পাওয়া যায়নি</h1><p class="muted">এই কোডের কোনো ফার্মেসি Lifolo-তে নেই। / No pharmacy with this code.</p></div>'));
      return;
    }
    // কাউন্টারের পোস্টারও এটাই (ছাপার বোতাম)
    const qr = await require('qrcode').toString(`https://blood-server-ashy.vercel.app/shop/${code}`, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' });
    res.status(200).send(page(`${name} · Lifolo`, `<div class="card" style="text-align:center"><span class="tag" style="background:#CFEDEF;color:#00363C">Lifolo ফার্মেসি</span>
<h1 style="margin-top:10px">${esc(name)}</h1>
<p>প্রেসক্রিপশনের ওষুধ কিনতে Lifolo অ্যাপে এই QR স্ক্যান করুন</p>
<div style="background:#fff;border-radius:16px;padding:12px;max-width:280px;margin:0 auto">${qr}</div>
<p>দোকানের কোড: <b style="font-size:30px;letter-spacing:6px">${esc(code)}</b></p>
<button onclick="window.print()" style="font:inherit;padding:10px 18px;border-radius:20px;border:0;background:#1F6F78;color:#fff">প্রিন্ট · Print</button>
<p class="muted">Lifolo অ্যাপে প্রেসক্রিপশন খুলে "দোকানে আছি" → "QR স্ক্যান করুন" চাপুন, অথবা এই কোড লিখুন — প্রেসক্রিপশন এই দোকানে চলে যাবে।<br>Open your prescription in the Lifolo app → "I'm at a pharmacy" → scan this QR or type the code.</p></div>`));
    return;
  }

  // প্রেসক্রিপশন যাচাই (/rx/{code}): ফার্মেসি QR স্ক্যান করে দেখে আসল কি না
  if (req.query.rx) {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    res.setHeader('Cache-Control', 'no-store');
    let x = null;
    try {
      x = await require('../lib/prescriptions').verify(req.query.rx);
    } catch (e) {
      console.error('rx verify failed', e);
    }
    if (!x) {
      res.status(404).send(page('Lifolo', '<div class="card"><h1>পাওয়া যায়নি</h1><p class="muted">এই কোডের কোনো প্রেসক্রিপশন Lifolo-তে নেই। / No prescription with this code.</p></div>'));
      return;
    }
    const d = new Date((x.createdAt || 0) + 6 * 3600 * 1000);
    const date = `${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`;
    const meds = x.medicines.map((m) => `<li>${esc([m.form, m.name, m.strength].filter(Boolean).join(' '))}${m.dose ? ` — ${esc(m.dose)}` : ''}${m.days ? ` · ${esc(m.days)} দিন` : ''}</li>`).join('');
    const body = `<div class="card">
${x.status === 'cancelled' ? '<span class="tag" style="background:#777;color:#fff">বাতিল · Cancelled</span>' : '<span class="tag" style="background:#CFEDEF;color:#00363C">আসল · Genuine</span>'}
<h1 style="margin-top:10px">${esc(x.doctor.name)}</h1>
<div class="muted">${esc(x.doctor.degrees)}${x.doctor.regNo ? ` · BMDC ${esc(x.doctor.regNo)}` : ''}${x.doctor.chamber ? ` · ${esc(x.doctor.chamber)}` : ''}</div>
<dl>
<dt>রোগী · Patient</dt><dd>${esc(x.patient.name)}${x.patient.age != null ? `, ${esc(x.patient.age)} বছর` : ''}</dd>
<dt>তারিখ · Date</dt><dd>${esc(date)}</dd>
${meds ? `<dt>ওষুধ · Medicines</dt><dd><ol style="margin:4px 0 0;padding-left:20px">${meds}</ol></dd>` : ''}
${x.tests.length ? `<dt>পরীক্ষা · Tests</dt><dd>${x.tests.map(esc).join(', ')}</dd>` : ''}
</dl></div>
<p class="muted" style="margin-top:16px">Lifolo-তে যাচাই করা ডাক্তারের দেওয়া প্রেসক্রিপশন। বাতিল দেখালে ওষুধ দেবেন না।<br>Issued on Lifolo by a verified doctor. Don't dispense if it shows cancelled.</p>`;
    res.status(200).send(page('প্রেসক্রিপশন যাচাই · Lifolo', body));
    return;
  }

  const id = String(req.query.id || '');
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  if (!R.isValidId(id)) {
    res.status(404).send(page('Lifolo', '<p>রিকোয়েস্টটা পাওয়া যায়নি। / Request not found.</p>'));
    return;
  }
  let r = null;
  try {
    const s = await db.doc(`requests/${id}`).get();
    if (s.exists) r = s.data();
  } catch (e) {
    console.error('share load failed', e);
  }
  if (!r) {
    res.status(404).send(page('Lifolo', '<p>রিকোয়েস্টটা পাওয়া যায়নি। / Request not found.</p>'));
    return;
  }

  const active = R.isActive(r);
  // কিছু ব্যাগ পাওয়া/নিশ্চিত হলে বাকিটা
  const left = active && R.bagsLeft(r) > 0 ? R.bagsLeft(r) : (r.bags || 1);
  const district = r.placeName || '';
  const districtEn = R.districtLabel(district, 'en');
  const when = r.neededAtMillis ? `${R.formatTimeBn(r.neededAtMillis)} / ${R.formatTimeEn(r.neededAtMillis)}` : '';
  const d = new Date(r.neededByMillis || 0);
  const date = `${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`;
  const intent = `intent://r/${encodeURIComponent(id)}#Intent;scheme=getblood;package=${PACKAGE};S.browser_fallback_url=${encodeURIComponent(APK_URL)};end`;
  const title = `${r.patientGroup} রক্ত দরকার / ${r.patientGroup} blood needed`;

  const body = `<div class="card">
<div class="row"><div class="group">${esc(r.patientGroup)}</div>
<div><h1>${esc(left)} ব্যাগ রক্ত দরকার</h1><div class="muted">${esc(left)} bag(s) of ${esc(r.patientGroup)} blood needed${left < (r.bags || 1) ? ` · মোট ${esc(r.bags)} ব্যাগের বাকি / of ${esc(r.bags)}` : ''}</div>
${r.urgent && active ? '<span class="tag">জরুরি · Urgent</span>' : ''}${!active ? '<span class="tag" style="background:#777">বন্ধ · Closed</span>' : ''}</div></div>
<dl>
<dt>হাসপাতাল · Hospital</dt><dd>${esc(r.hospital)}</dd>
<dt>জেলা · District</dt><dd>${esc(district)}${districtEn !== district ? ` · ${esc(districtEn)}` : ''}</dd>
<dt>কবে লাগবে · Needed by</dt><dd>${esc(date)}${when ? ` · ${esc(when)}` : ''}</dd>
<dt>অবস্থা · Status</dt><dd>${!active ? 'বন্ধ · Closed'
  : r.fulfilled ? 'ডোনার পাওয়া গেছে · Donors found'
  : (r.acceptedCount || 0) > 0 ? `${esc(r.acceptedCount)} জন রাজি · ${esc(r.acceptedCount)} agreed`
  : '<b>এখনো কোনো ডোনার নেই · No donor yet</b>'}</dd>
<dt>যাতায়াত খরচ · Travel cost</dt><dd>${r.travelFare ? 'দেওয়া হবে · Will be paid' : 'দেওয়া হবে না · Not provided'}</dd>
</dl></div>
${active ? `<a class="btn primary" href="${esc(intent)}">অ্যাপে খুলে সাড়া দিন · Open in app</a>` : ''}
${active ? `<a class="btn secondary" href="/request/${encodeURIComponent(id)}">ওয়েবে খুলে সাড়া দিন · Open on the web</a>` : ''}
<a class="btn secondary" href="${esc(APK_URL)}">অ্যাপ নামান · Download Lifolo</a>
<p class="muted" style="margin-top:20px">রক্ত কেনাবেচা নিষিদ্ধ। চূড়ান্তভাবে ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।<br>Blood can't be bought or sold. The blood bank decides after cross-matching.</p>`;
  res.status(200).send(page(title, body));
};
