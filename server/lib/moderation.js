// রিপোর্ট: প্রশ্ন, উত্তর বা টিপস ভুল/ক্ষতিকর মনে হলে যে কেউ রিপোর্ট করেন, অ্যাডমিন দেখে মুছে দেন বা বাদ দেন।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');
const { isAdminToken } = require('./admin');
const { useQuota } = require('./community-db');

const KINDS = ['question', 'answer', 'tip'];

function targetPath(kind, id, answerId) {
  if (kind === 'question') return `questions/${id}`;
  if (kind === 'answer') return `questions/${id}/answers/${answerId}`;
  return `tips/${id}`;
}

/** লক্ষ্য মুছে ফেলা (প্রশ্ন হলে তার সব উত্তর সহ) */
async function removeTarget(path) {
  const ref = db.doc(path);
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
    if (!(await db.doc(path).get()).exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    await useQuota(req, user.uid, 'report');
    // একজন একই জিনিস একবারই রিপোর্ট করতে পারেন
    const docId = `${path.replace(/\//g, '_')}__${user.uid}`.slice(0, 1400);
    await db.doc(`reports/${docId}`).set({ kind, targetPath: path, reporterUid: user.uid, status: 'open', createdAt: Date.now() });
    return { ok: true };
  },

  async admin_reports(req, user) {
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const snap = await db.collection('reports').where('status', '==', 'open').limit(200).get();
    // একই জিনিসের একাধিক রিপোর্ট একসাথে
    const groups = new Map();
    snap.docs.forEach((d) => {
      const p = d.get('targetPath');
      const g = groups.get(p) || { targetPath: p, kind: d.get('kind'), count: 0 };
      g.count++;
      groups.set(p, g);
    });
    const list = [...groups.values()];
    const targets = list.length ? await db.getAll(...list.map((g) => db.doc(g.targetPath))) : [];
    const items = [];
    for (let i = 0; i < list.length; i++) {
      const t = targets[i];
      if (!t.exists) continue;
      items.push({
        targetPath: list[i].targetPath,
        kind: list[i].kind,
        count: list[i].count,
        text: [t.get('title'), t.get('text') || t.get('body')].filter(Boolean).join('\n'),
        authorName: t.get('authorName') || '',
      });
    }
    return { items };
  },

  async admin_report_resolve(req, user) {
    if (!isAdminToken(user)) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
    const path = req.body?.targetPath;
    if (typeof path !== 'string' || !/^(questions\/[A-Za-z0-9_-]+(\/answers\/[A-Za-z0-9_-]+)?|tips\/[A-Za-z0-9_-]+)$/.test(path)) {
      throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    }
    if (req.body?.remove === true) await removeTarget(path);
    const snap = await db.collection('reports').where('targetPath', '==', path).get();
    const batch = db.batch();
    snap.docs.forEach((d) => batch.update(d.ref, { status: req.body?.remove === true ? 'removed' : 'dismissed', resolvedAt: Date.now() }));
    await batch.commit();
    return { ok: true };
  },
};
