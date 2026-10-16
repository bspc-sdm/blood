// রিপোর্ট: প্রশ্ন, উত্তর বা টিপস ভুল/ক্ষতিকর মনে হলে যে কেউ রিপোর্ট করেন, অ্যাডমিন দেখে মুছে দেন বা বাদ দেন।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const { isAdmin } = require('./admin');
const { useQuota } = require('./community-db');

const K = require('./care');
// homevisit: আগের অনুরোধ-ব্যবস্থার খোলা রিপোর্ট (নতুন নেওয়া হয় না); homecare: বাসায় সেবার তালিকার ডাক্তার/নার্স
// provider: চেম্বারের তালিকার ডাক্তার/নার্স (রিপোর্ট টিকলে পুরো তালিকা বন্ধ)
const KINDS = ['question', 'answer', 'tip', 'homecare', 'provider', 'ambulance', 'pharmacy', 'bloodbank'];
const PATH_RE = /^(questions\/[A-Za-z0-9_-]+(\/answers\/[A-Za-z0-9_-]+)?|tips\/[A-Za-z0-9_-]+|homeVisits\/[A-Za-z0-9_-]+|providers\/[A-Za-z0-9_-]+|ambulances\/[A-Za-z0-9_-]+|pharmacies\/[A-Za-z0-9_-]+|bloodBanks\/[A-Za-z0-9_-]+)$/;

function targetPath(kind, id, answerId) {
  if (kind === 'question') return `questions/${id}`;
  if (kind === 'answer') return `questions/${id}/answers/${answerId}`;
  if (kind === 'homecare' || kind === 'provider') return `providers/${id}`;
  if (kind === 'ambulance') return `ambulances/${id}`;
  if (kind === 'pharmacy') return `pharmacies/${id}`;
  if (kind === 'bloodbank') return `bloodBanks/${id}`;
  return `tips/${id}`;
}

/** লক্ষ্য মুছে ফেলা (প্রশ্ন হলে তার সব উত্তর সহ) */
async function removeTarget(path, reported, kind, reason = '') {
  if (path.startsWith('providers/') && kind === 'provider') {
    await require('./apptcancel').suspendProvider(path.split('/')[1], 'report');
    return;
  }
  const ref = db.doc(path);
  if (path.startsWith('ambulances/') || path.startsWith('pharmacies/') || path.startsWith('bloodBanks/')) {
    // মোছা হয় না, বন্ধ (suspended) — তালিকায় আর দেখায় না, মালিক নিজে আবার চালু করতে পারেন না
    const s = await ref.get();
    if (s.exists && s.get('status') !== 'suspended') {
      // কারণ মালিক নিজের কার্ডে আর নোটিফিকেশনে দেখেন (কেন বন্ধ, কী নিয়ে আপিল করবেন)
      await ref.update({ status: 'suspended', suspendedAt: Date.now(), statusReason: reason || '' });
      const listingKind = path.startsWith('ambulances/') ? 'ambulance' : path.startsWith('pharmacies/') ? 'pharmacy' : 'bloodbank';
      const x = { ...s.data(), ownerUid: s.get('ownerUid') || (listingKind === 'pharmacy' ? s.id : null) };
      await require('./sanctions').onStatusChange(listingKind, x, s.get('status'), 'suspended');
      await require('./listingreview').tellSuspendedByReport(listingKind, x, reason);
    }
    return;
  }
  if (path.startsWith('providers/')) {
    // ডাক্তার/নার্স মোছা হয় না; বাসায় সেবা বন্ধ (তালিকায় আর দেখায় না, নিজে আবার চালু করতে পারেন না)
    await require('./homecare').blockHomeCare(path.split('/')[1]);
    return;
  }
  if (path.startsWith('homeVisits/')) {
    // আগের অনুরোধ-ব্যবস্থার রিপোর্ট: নার্সকে রিপোর্ট করা হলে তাঁর বাসায় সেবা বন্ধ (রোগীর অনুরোধ এমনিতেই বন্ধ)
    if (reported && reported.role === 'nurse') await require('./homecare').blockHomeCare(reported.uid);
    return;
  }
  if (path.startsWith('questions/') && path.split('/').length === 2) {
    await db.recursiveDelete(ref);
  } else if (path.includes('/answers/')) {
    const s = await ref.get();
    if (s.exists) {
      const batch = db.batch();
      batch.delete(ref);
      batch.update(ref.parent.parent, { answerCount: FieldValue.increment(-1) });
      await batch.commit();
    }
  } else {
    await ref.delete();
  }
}

/**
 * প্রশ্ন/উত্তর/টিপস বদলালে: অ্যাডমিন যে রিপোর্টগুলো "রেখে দিন" করেছিলেন সেগুলো আবার খোলা
 * (রিপোর্টের পর ভালো করে, রেখে দেওয়ার পর আবার খারাপ করা আটকাতে)
 */
async function reopenOnEdit(path) {
  try {
    const snap = await db.collection('modReports').where('targetPath', '==', path).where('status', '==', 'dismissed').limit(100).get();
    if (snap.empty) return 0;
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { status: 'open', reopenedAt: Date.now(), reopenReason: 'edited' }));
    await batch.commit();
    return snap.size;
  } catch (e) {
    console.error('report reopen failed', e.message);
    return 0;
  }
}

/** মালিকের হিসাবে রিপোর্ট: শুধু খোলা আর টেকা (অ্যাডমিন "রেখে দিন" বললে গোনা হয় না) */
async function reportCount(path) {
  const [open, removed] = await Promise.all(['open', 'removed'].map((s) =>
    db.collection('modReports').where('targetPath', '==', path).where('status', '==', s).count().get()));
  return open.data().count + removed.data().count;
}

/** খোলা রিপোর্ট থাকলে মুছে আবার যোগ করে পরিষ্কার রেকর্ড পাওয়া আটকাতে */
async function assertNoOpenReports(req, path) {
  const s = await db.collection('modReports').where('targetPath', '==', path).where('status', '==', 'open').limit(1).get();
  if (!s.empty) {
    throw new HttpError(409, C.tr(req, 'This has an open report. It can be removed after the Lifolo team reviews it.', 'এটার একটা রিপোর্ট খোলা আছে। Lifolo টিম দেখার পর মুছতে পারবেন।'));
  }
}

module.exports = {
  async report(req, user) {
    const { kind, id, answerId } = req.body || {};
    if (!KINDS.includes(kind) || !R.isValidId(id) || (kind === 'answer' && !R.isValidId(answerId))) {
      throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    }
    const path = targetPath(kind, id, answerId);
    const target = await db.doc(path).get();
    if (!target.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    let reason = '';
    let reported = null;
    if (kind === 'homecare' && (id === user.uid || !(target.get('homeVisit') && target.get('homeVisit').enabled))) {
      throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    }
    if (kind === 'provider' && id === user.uid) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    // অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংক/বাসায় সেবা: অ্যাপ থেকে ফোন করেছেন এমন মানুষই রিপোর্ট করতে পারেন (প্রতিযোগীর ভুয়া রিপোর্ট আটকাতে)
    const callDoc = { ambulance: 'ambulanceCalls', pharmacy: 'pharmacyCalls', bloodbank: 'bloodBankCalls', homecare: 'homeCalls' }[kind];
    // "ওষুধ খুঁজুন"-এ দোকান "আছে" বলেছিল (গিয়ে দেখলেন নেই) — সেটাও রিপোর্ট করা যায়
    const answeredFind = async () => kind === 'pharmacy' && (await db.collection('medFinds').where('uid', '==', user.uid).limit(30).get())
      .docs.some((d) => (d.get('answers') || {})[id]);
    if (callDoc && !(await db.doc(`${callDoc}/${user.uid}_${id}`).get()).exists && !(await answeredFind())) {
      throw new HttpError(403, C.tr(req, 'You can report after calling from the app', 'অ্যাপ থেকে ফোন করার পর রিপোর্ট করতে পারবেন'));
    }
    if (kind === 'ambulance' || kind === 'pharmacy' || kind === 'homecare' || kind === 'bloodbank' || kind === 'provider') {
      reason = C.cleanText(req.body?.reason, 300);
      if (!reason) throw new HttpError(400, C.tr(req, 'Write what happened', 'কী হয়েছে লিখুন'));
    }
    await useQuota(req, user.uid, 'report');
    // একজন একই জিনিস একবারই রিপোর্ট করতে পারেন (অ্যাডমিন বাদ দেওয়ার পর আবার খোলা যায় না)
    const docId = `${path.replace(/\//g, '_')}__${user.uid}${reported ? `__${reported.uid}` : ''}${kind === 'provider' ? '__provider' : ''}`.slice(0, 1400);
    const ref = db.doc(`modReports/${docId}`);
    const prev = await ref.get();
    // আগে রিপোর্ট করেছিলেন: লেখক পরে লেখা বদলালে (আর অ্যাডমিন আগেরটা রেখে দিয়েছিলেন) আবার রিপোর্ট করা যায়
    const editedAt = target.get('editedAt') || 0;
    if (prev.exists && !(prev.get('status') === 'dismissed' && editedAt > (prev.get('createdAt') || 0))) return { ok: true };
    await ref.set({
      kind,
      targetPath: path,
      reporterUid: user.uid,
      reason,
      // রিপোর্টের সময়ের লেখা (পরে বদলালে অ্যাডমিন দুটো মিলিয়ে দেখেন)
      ...(['question', 'answer', 'tip'].includes(kind) ? { snapshot: [target.get('title'), target.get('text') || target.get('body')].filter(Boolean).join('\n').slice(0, 3000) } : {}),
      ...(reported ? { reportedUid: reported.uid, reportedRole: reported.role, reportedName: reported.name } : {}),
      status: 'open',
      createdAt: Date.now(),
    });
    return { ok: true };
  },

  async admin_reports(req, user) {
    if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    // modReports অ্যাপ সরাসরি লিখতে পারে না (Firestore নিয়মে নেই), তাই কেউ ভুয়া রিপোর্ট দিয়ে তালিকা ভরাতে পারে না
    const snap = await db.collection('modReports').where('status', '==', 'open').limit(200).get();
    // ডাক্তারের চেম্বারের রিপোর্ট শুধু ওয়েব প্যানেলে (আগের অ্যাপে "সরান" চাপলে ভুল ব্যবস্থা হতো)
    const panel = req.body?.panel === true;
    // একই জিনিসের একাধিক রিপোর্ট একসাথে
    const groups = new Map();
    snap.docs.forEach((d) => {
      const p = d.get('targetPath');
      if (typeof p !== 'string' || !PATH_RE.test(p)) return;
      if (d.get('kind') === 'provider' && !panel) return;
      // একই ডাক্তারের "বাসায় সেবা" আর "চেম্বার" রিপোর্ট আলাদা (বাদ দিলে আলাদা ব্যবস্থা)
      const key = (d.get('reportedUid') ? `${p}|${d.get('reportedUid')}` : p) + (d.get('kind') === 'provider' ? '|provider' : '');
      const g = groups.get(key) || {
        targetPath: p,
        kind: d.get('kind'),
        count: 0,
        reasons: [],
        reportedUid: d.get('reportedUid') || null,
        reportedRole: d.get('reportedRole') || null,
        reportedName: d.get('reportedName') || '',
        snapshot: d.get('snapshot') || '',
      };
      g.count++;
      if (d.get('reason')) g.reasons.push(d.get('reason'));
      groups.set(key, g);
    });
    const list = [...groups.values()];
    const targets = list.length ? await db.getAll(...list.map((g) => db.doc(g.targetPath))) : [];
    const items = [];
    const gone = [];
    for (let i = 0; i < list.length; i++) {
      const t = targets[i];
      if (!t.exists) {
        gone.push(list[i].targetPath);
        continue;
      }
      items.push({
        targetPath: list[i].targetPath,
        kind: list[i].kind,
        count: list[i].count,
        text: list[i].kind === 'homevisit'
          ? [
            `${list[i].reportedRole === 'patient' ? 'Patient' : 'Nurse'} reported: ${list[i].reportedName || '-'}`,
            `${(t.get('services') || []).join(', ')} · ${t.get('status')}`,
            ...list[i].reasons,
          ].join('\n')
          : ['ambulance', 'pharmacy', 'bloodbank'].includes(list[i].kind)
            ? [`${t.get('name') || ''} · ${K.fixPhone(t.get('phone'))} · ${t.get('district') || ''}`, ...list[i].reasons].join('\n')
            : list[i].kind === 'provider'
              ? [`${t.get('type') === 'doctor' ? 'ডাক্তার' : 'নার্স'} (চেম্বার): ${t.get('name') || ''} · ${K.chambersOf(t.data()).map((c) => c.hospitalName).join(', ')}`, ...list[i].reasons].join('\n')
            : list[i].kind === 'homecare'
              ? [`${t.get('type') === 'doctor' ? 'ডাক্তার' : 'নার্স'}: ${t.get('name') || ''} · ${K.fixPhone(t.get('homeVisit.phone'))}`, ...list[i].reasons].join('\n')
            : [t.get('title'), t.get('text') || t.get('body')].filter(Boolean).join('\n'),
        // বেনামি প্রশ্নে প্রশ্নকারীর নাম অ্যাডমিনও দেখেন না
        authorName: list[i].kind === 'homevisit' ? list[i].reportedName : t.get('anonymous') === true ? 'বেনামি' : t.get('authorName') || t.get('name') || '',
        // রিপোর্টের পর লেখা বদলে থাকলে রিপোর্টের সময়ের লেখা
        ...(list[i].snapshot && list[i].snapshot !== [t.get('title'), t.get('text') || t.get('body')].filter(Boolean).join('\n').slice(0, 3000)
          ? { snapshot: list[i].snapshot } : {}),
        reportedUid: list[i].reportedUid,
        reportedRole: list[i].reportedRole,
        // ওয়েব প্যানেলে আলাদা করে দেখানোর জন্য (অ্যাপ এটা ব্যবহার করে না)
        reasons: list[i].reasons.slice(0, 10),
      });
    }
    // মুছে যাওয়া পোস্টের রিপোর্ট বন্ধ করে দেওয়া, যাতে তালিকা ভরে না যায়
    if (gone.length) {
      const batch = db.batch();
      snap.docs.filter((d) => gone.includes(d.get('targetPath'))).forEach((d) => batch.update(d.ref, { status: 'dismissed' }));
      await batch.commit();
    }
    return { items };
  },

  async admin_report_resolve(req, user) {
    if (!(await isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const path = req.body?.targetPath;
    if (typeof path !== 'string' || !PATH_RE.test(path)) {
      throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    }
    const reportedUid = R.isValidId(req.body?.reportedUid) ? req.body.reportedUid : null;
    let snap = await db.collection('modReports').where('targetPath', '==', path).get();
    // হোম ভিজিটে একই ভিজিটে দুজনকে আলাদা রিপোর্ট করা যায়; শুধু এই মানুষের রিপোর্টগুলো
    let docs = reportedUid ? snap.docs.filter((d) => d.get('reportedUid') === reportedUid) : snap.docs;
    // ডাক্তার/নার্স: চেম্বারের রিপোর্ট আর বাসায় সেবার রিপোর্ট আলাদা করে মীমাংসা
    if (path.startsWith('providers/')) {
      const wantProvider = req.body?.kind === 'provider';
      docs = docs.filter((d) => (d.get('kind') === 'provider') === wantProvider);
    }
    if (req.body?.remove === true) {
      const r = docs.find((d) => d.get('reportedUid'));
      // মালিককে যে কারণ দেখানো হবে: অ্যাডমিন লিখে দিলে সেটা, নইলে সাধারণ কথা — রিপোর্টকারীর নিজের লেখা নয়
      // (শুধু ফোন করা মানুষ রিপোর্ট করতে পারেন, লেখা দেখে মালিক চিনে ফেলতে পারেন)
      const reason = String(req.body?.reason || '').trim().slice(0, 200) || 'ব্যবহারকারীর রিপোর্ট যাচাই করে সত্য পাওয়া গেছে';
      await removeTarget(path, r ? { uid: r.get('reportedUid'), role: r.get('reportedRole') } : null, req.body?.kind, reason);
    }
    snap = { docs };
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { status: req.body?.remove === true ? 'removed' : 'dismissed', resolvedAt: Date.now() }));
    await batch.commit();
    return { ok: true };
  },
};

// সহায়ক ফাংশন — api/community.js সব export-কে অ্যাকশন বানায়, তাই এগুলো গোনার বাইরে (enumerable নয়)
Object.defineProperty(module.exports, 'reportCount', { value: reportCount, enumerable: false });
Object.defineProperty(module.exports, 'assertNoOpenReports', { value: assertNoOpenReports, enumerable: false });
Object.defineProperty(module.exports, 'reopenOnEdit', { value: reopenOnEdit, enumerable: false });
