// রিপোর্ট: প্রশ্ন, উত্তর বা টিপস ভুল/ক্ষতিকর মনে হলে যে কেউ রিপোর্ট করেন, অ্যাডমিন দেখে মুছে দেন বা বাদ দেন।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const { isAdminToken } = require('./admin');
const { useQuota } = require('./community-db');

const KINDS = ['question', 'answer', 'tip', 'homevisit'];
const PATH_RE = /^(questions\/[A-Za-z0-9_-]+(\/answers\/[A-Za-z0-9_-]+)?|tips\/[A-Za-z0-9_-]+|homeVisits\/[A-Za-z0-9_-]+)$/;

function targetPath(kind, id, answerId) {
  if (kind === 'question') return `questions/${id}`;
  if (kind === 'answer') return `questions/${id}/answers/${answerId}`;
  if (kind === 'homevisit') return `homeVisits/${id}`;
  return `tips/${id}`;
}

/** লক্ষ্য মুছে ফেলা (প্রশ্ন হলে তার সব উত্তর সহ) */
async function removeTarget(path, reported) {
  const ref = db.doc(path);
  if (path.startsWith('homeVisits/')) {
    // হোম ভিজিট মোছা হয় না; যাকে রিপোর্ট করা হয়েছে (নার্স বা রোগী) তাকে হোম ভিজিট থেকে বাদ দেওয়া হয়
    if (reported) await require('./homevisit').blockFromHomeVisits(reported.uid, reported.role);
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
    if (kind === 'homevisit') {
      // শুধু রোগী আর যে নার্স নিয়েছেন তারাই রিপোর্ট করতে পারেন; কাকে রিপোর্ট করা হলো তা এখনই লিখে রাখা হয়
      const past = target.get('pastNurseUids') || [];
      const isPatient = target.get('patientUid') === user.uid;
      const isNurse = (!!target.get('nurseUid') && target.get('nurseUid') === user.uid) || past.includes(user.uid);
      if (!isPatient && !isNurse) throw new HttpError(403, C.tr(req, 'Not allowed', 'অনুমতি নেই'));
      // রোগী রিপোর্ট করলে: এখনকার নার্স, না থাকলে যিনি নিয়ে সরে গেছেন
      reported = isPatient
        ? (target.get('nurseUid')
          ? { uid: target.get('nurseUid'), role: 'nurse', name: target.get('nurseName') || '' }
          : { uid: past[past.length - 1], role: 'nurse', name: target.get('pastNurseName') || '' })
        : { uid: target.get('patientUid'), role: 'patient', name: target.get('patientName') || '' };
      if (!reported.uid) throw new HttpError(400, C.tr(req, 'No nurse has accepted yet', 'এখনো কোনো নার্স নেননি'));
      reason = C.cleanText(req.body?.reason, 300);
      if (!reason) throw new HttpError(400, C.tr(req, 'Write what happened', 'কী হয়েছে লিখুন'));
    }
    await useQuota(req, user.uid, 'report');
    // একজন একই জিনিস একবারই রিপোর্ট করতে পারেন (অ্যাডমিন বাদ দেওয়ার পর আবার খোলা যায় না)
    const docId = `${path.replace(/\//g, '_')}__${user.uid}${reported ? `__${reported.uid}` : ''}`.slice(0, 1400);
    const ref = db.doc(`modReports/${docId}`);
    if ((await ref.get()).exists) return { ok: true };
    await ref.set({
      kind,
      targetPath: path,
      reporterUid: user.uid,
      reason,
      ...(reported ? { reportedUid: reported.uid, reportedRole: reported.role, reportedName: reported.name } : {}),
      status: 'open',
      createdAt: Date.now(),
    });
    return { ok: true };
  },

  async admin_reports(req, user) {
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    // modReports অ্যাপ সরাসরি লিখতে পারে না (Firestore নিয়মে নেই), তাই কেউ ভুয়া রিপোর্ট দিয়ে তালিকা ভরাতে পারে না
    const snap = await db.collection('modReports').where('status', '==', 'open').limit(200).get();
    // একই জিনিসের একাধিক রিপোর্ট একসাথে
    const groups = new Map();
    snap.docs.forEach((d) => {
      const p = d.get('targetPath');
      if (typeof p !== 'string' || !PATH_RE.test(p)) return;
      const key = d.get('reportedUid') ? `${p}|${d.get('reportedUid')}` : p;
      const g = groups.get(key) || {
        targetPath: p,
        kind: d.get('kind'),
        count: 0,
        reasons: [],
        reportedUid: d.get('reportedUid') || null,
        reportedRole: d.get('reportedRole') || null,
        reportedName: d.get('reportedName') || '',
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
          : [t.get('title'), t.get('text') || t.get('body')].filter(Boolean).join('\n'),
        authorName: list[i].kind === 'homevisit' ? list[i].reportedName : t.get('authorName') || '',
        reportedUid: list[i].reportedUid,
        reportedRole: list[i].reportedRole,
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
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const path = req.body?.targetPath;
    if (typeof path !== 'string' || !PATH_RE.test(path)) {
      throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    }
    const reportedUid = R.isValidId(req.body?.reportedUid) ? req.body.reportedUid : null;
    let snap = await db.collection('modReports').where('targetPath', '==', path).get();
    // হোম ভিজিটে একই ভিজিটে দুজনকে আলাদা রিপোর্ট করা যায়; শুধু এই মানুষের রিপোর্টগুলো
    const docs = reportedUid ? snap.docs.filter((d) => d.get('reportedUid') === reportedUid) : snap.docs;
    if (req.body?.remove === true) {
      const r = docs.find((d) => d.get('reportedUid'));
      await removeTarget(path, r ? { uid: r.get('reportedUid'), role: r.get('reportedRole') } : null);
    }
    snap = { docs };
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { status: req.body?.remove === true ? 'removed' : 'dismissed', resolvedAt: Date.now() }));
    await batch.commit();
    return { ok: true };
  },
};
