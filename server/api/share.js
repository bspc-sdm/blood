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
<div class="logo"><div class="dot">+</div>Dorkar</div>
${body}
</main></body></html>`;
}

const CAT = { health: 'স্বাস্থ্যসেবা', education: 'শিক্ষা', sales: 'বিক্রয় ও মার্কেটিং', accounts: 'হিসাব ও অর্থ', office: 'অফিস ও প্রশাসন', it: 'আইটি', driving: 'গাড়ি চালনা ও ডেলিভারি', factory: 'কারখানা', hospitality: 'হোটেল ও রেস্টুরেন্ট', security: 'নিরাপত্তা', construction: 'নির্মাণ ও মেরামত', domestic: 'গৃহস্থালি কাজ', other: 'অন্যান্য' };
const ym = (v) => (v ? v.split('-').reverse().join('/') : '');

async function cvPage(uid, res) { // FEATURE:jobs
  let cv = null;
  try {
    cv = await require('../lib/cv').publicCv(uid);
  } catch (e) {
    console.error('cv load failed', e);
  }
  if (!cv) {
    res.status(404).send(page('সিভি / CV', '<p>এই সিভি দেখা যাচ্ছে না। / This CV is not public.</p>'));
    return;
  }
  const sec = (title, inner) => (inner ? `<h2>${esc(title)}</h2>${inner}` : '');
  const exp = cv.experience.map((e) => `<div class="item"><b>${esc(e.title)}</b> · ${esc(e.org)}${e.verified ? ' <span class="tag" style="background:#CFEDEF;color:#00363C">যাচাই করা · Verified</span>' : ''}<div class="muted">${esc(ym(e.from))}${e.from ? ' – ' : ''}${e.current ? 'এখন পর্যন্ত' : esc(ym(e.to))}</div>${e.details ? `<p>${esc(e.details)}</p>` : ''}</div>`).join('');
  const edu = cv.education.map((e) => `<div class="item"><b>${esc(e.degree)}</b> · ${esc(e.institute)}<div class="muted">${esc(e.year || '')}${e.result ? ` · ${esc(e.result)}` : ''}</div></div>`).join('');
  const BADGE = { doctor: 'ডাক্তার · Doctor', nurse: 'নার্স · Nurse' };
  const roles = [
    cv.bloodGroup ? `রক্তদাতা ${esc(cv.bloodGroup)}${cv.donationCount ? ` · ${esc(cv.donationCount)} বার` : ''} · Blood donor` : '',
    cv.badge ? `${esc(BADGE[cv.badge] || 'যাচাই করা পেশাজীবী · Verified professional')}` : '',
    cv.employerVerified ? 'যাচাই করা নিয়োগকর্তা · Verified employer' : '',
  ].filter(Boolean).map((t) => `<span class="tag" style="background:#E8DEF8;color:#21005D">${t}</span>`).join(' ');
  const links = (cv.links || []).map((l) => `<div class="item"><a href="${esc(l)}" target="_blank" rel="noopener noreferrer nofollow">${esc(l.replace(/^https?:\/\//, ''))}</a></div>`).join('');
  const certs = cv.certs.map((e) => `<div class="item"><b>${esc(e.name)}</b>${e.issuer ? ` · ${esc(e.issuer)}` : ''}${e.year ? ` <span class="muted">${esc(e.year)}</span>` : ''}</div>`).join('');
  const chips = (list) => (list.length ? `<div class="chips">${list.map((x) => `<span>${esc(x)}</span>`).join('')}</div>` : '');
  const photo = cv.photo ? `<img class="ph" src="data:image/jpeg;base64,${esc(cv.photo)}" alt="">` : `<div class="group">${esc((cv.name || '?').slice(0, 1))}</div>`;
  const body = `<style>.item{margin:10px 0}.item p{margin:4px 0 0}h2{font-size:17px;margin:22px 0 6px}.chips span{display:inline-block;background:#CFEDEF;color:#00363C;border-radius:999px;padding:3px 10px;margin:3px 4px 0 0;font-size:14px}.ph{width:72px;height:72px;border-radius:50%;object-fit:cover}</style>
<div class="card">
<div class="row">${photo}<div><h1>${esc(cv.name)}</h1><div class="muted">${esc(cv.headline)}</div><div class="muted">${esc(cv.district)}</div>
${cv.openToWork ? '<span class="tag" style="background:#CFEDEF;color:#00363C">কাজ খুঁজছেন · Open to work</span>' : ''} ${roles}</div></div>
${cv.phone || cv.email ? `<dl>${cv.phone ? `<dt>ফোন · Phone</dt><dd>${esc(cv.phone)}</dd>` : ''}${cv.email ? `<dt>ইমেইল · Email</dt><dd>${esc(cv.email)}</dd>` : ''}</dl>` : ''}
${sec('নিজের সম্পর্কে · About', cv.about ? `<p>${esc(cv.about)}</p>` : '')}
${sec('অভিজ্ঞতা · Experience', exp)}
${sec('শিক্ষা · Education', edu)}
${sec('দক্ষতা · Skills', chips(cv.skills))}
${sec('ভাষা · Languages', chips(cv.languages))}
${sec('প্রশিক্ষণ ও সনদ · Certificates', certs)}
${sec('লিংক · Links', links)}
${sec('যে কাজ খুঁজছেন · Looking for', chips(cv.wantCategories.map((c) => CAT[c] || c)))}
</div>`;
  res.status(200).send(page(`${cv.name} — সিভি`, body));
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

  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  // সিভির শেয়ার লিংক (/cv/{uid}) — মালিক "সবাই দেখবে" চালু রাখলে তবেই
  if (req.query.cv) { // FEATURE:jobs
    await cvPage(String(req.query.cv), res);
    return;
  }
  const id = String(req.query.id || '');
  if (!R.isValidId(id)) {
    res.status(404).send(page('Dorkar', '<p>রিকোয়েস্টটা পাওয়া যায়নি। / Request not found.</p>'));
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
    res.status(404).send(page('Dorkar', '<p>রিকোয়েস্টটা পাওয়া যায়নি। / Request not found.</p>'));
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
<a class="btn secondary" href="${esc(APK_URL)}">অ্যাপ নামান · Download Dorkar</a>
<p class="muted" style="margin-top:20px">রক্ত কেনাবেচা নিষিদ্ধ। চূড়ান্তভাবে ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।<br>Blood can't be bought or sold. The blood bank decides after cross-matching.</p>`;
  res.status(200).send(page(title, body));
};
