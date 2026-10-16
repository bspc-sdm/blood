// অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক — অ্যাডমিনের সিদ্ধান্ত এক জায়গায়:
// কারণ লেখা (মালিক নোটিফিকেশন আর নিজের কার্ডে দেখেন), অনুমোদনের সময়ের তথ্য রেখে দেওয়া (lastApproved),
// আর অনুমোদিত তালিকার বদল বাতিল হলে আগের অনুমোদিত তথ্য ফিরিয়ে আনা — পুরো তালিকা হারায় না।
const { db, FieldValue, HttpError } = require('./firebase');
const R = require('./rules');
const C = require('./community');

// তালিকার তথ্য নয় — অবস্থা, হিসাব আর সময়
const META = new Set([
  'status', 'ownerUid', 'createdAt', 'updatedAt', 'reviewedAt', 'reviewedBy', 'suspendedAt', 'approvedName', 'lastApproved',
  'statusReason', 'ratingSum', 'ratingCount', 'available', 'availableAt', 'busyAt', 'closedUntil', 'editRejectedAt',
  'updatedAt', 'findDay', 'findCount', 'findOff', 'editedByAdmin', 'addedByAdmin', 'editRejectedReason',
]);

function snapshot(x) {
  return Object.fromEntries(Object.entries(x || {}).filter(([k]) => !META.has(k)));
}

/** অনুমোদিত তথ্যের সাথে এখনকার তফাত (প্যানেলে "আগে: …") */
function changedFields(x) {
  const before = x && x.lastApproved;
  if (!before) return [];
  const now = snapshot(x);
  return [...new Set([...Object.keys(before), ...Object.keys(now)])]
    .filter((k) => JSON.stringify(before[k] ?? null) !== JSON.stringify(now[k] ?? null))
    .map((k) => ({ field: k, before: before[k] ?? null, after: now[k] ?? null }));
}

const LABEL = {
  ambulance: ['ambulance', 'অ্যাম্বুলেন্স'],
  pharmacy: ['pharmacy', 'ফার্মেসি'],
  bloodbank: ['blood bank', 'ব্লাড ব্যাংক'],
};

async function tellOwner(ownerUid, title, body, kind) {
  if (!ownerUid) return;
  await require('./requestend').pushUsers([ownerUid], (u, lang) => ({
    title: R.L(lang, title[0], title[1]),
    body: typeof body === 'function' ? body(lang) : body || '',
    // kind: অ্যাপ নোটিফিকেশনে ঠিক আইকন দেখায় (অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক)
    data: { type: 'services', ...(kind ? { kind } : {}) },
  }));
}

/**
 * অ্যাডমিনের সিদ্ধান্ত। kind: ambulance | pharmacy | bloodbank
 * reason: বাতিল/বন্ধের কারণ (মালিক দেখেন)
 */
async function resolveListing(req, user, kind, collection, id) {
  const status = ['approved', 'rejected', 'suspended'].includes(req.body?.status) ? req.body.status : req.body?.approve === true ? 'approved' : 'rejected';
  if (!R.isValidId(id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
  const reason = C.cleanText(req.body?.reason, 200) || '';
  const ref = db.doc(`${collection}/${id}`);
  const s = await ref.get();
  if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
  const prev = s.get('status');
  if (prev === status) return { ok: true, unchanged: true };
  // পুরোনো ফার্মেসির নথিতে ownerUid না-ও থাকতে পারে — ফার্মেসির id-ই মালিকের uid
  const x = { ...s.data(), ownerUid: s.get('ownerUid') || (collection === 'pharmacies' ? id : null) };
  const [en, bn] = LABEL[kind];
  const withReason = (lang) => [x.name, reason ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : ''].filter(Boolean).join(' · ');

  // অনুমোদিত তালিকার বদল বাতিল: আগের অনুমোদিত তথ্য ফিরে আসে, তালিকায় থাকে
  if (status === 'rejected' && prev === 'pending' && x.lastApproved) {
    // বাতিল হওয়া বদলে নতুন যোগ হওয়া ঘরগুলোও মুছে যায় (আগের অনুমোদিত তথ্যে যা ছিল না)
    const extra = Object.fromEntries(Object.keys(snapshot(x)).filter((k) => !(k in x.lastApproved)).map((k) => [k, FieldValue.delete()]));
    await ref.update({
      ...extra, ...x.lastApproved, approvedName: x.lastApproved.name || x.approvedName || x.name, status: 'approved',
      // কারণ শুধু নোটিফিকেশনে আর প্যানেলের নোটে; তালিকা চালুই থাকে
      statusReason: FieldValue.delete(), editRejectedReason: reason, editRejectedAt: Date.now(), reviewedAt: Date.now(), reviewedBy: user.uid,
    });
    await tellOwner(x.ownerUid, [`Your ${en} changes were not accepted — the earlier details stay listed`, `আপনার ${bn}-এর বদল গ্রহণ হয়নি — আগের তথ্যই তালিকায় আছে`], withReason, kind);
    return { ok: true, restored: true };
  }

  await ref.update({
    status,
    reviewedAt: Date.now(),
    reviewedBy: user.uid,
    ...(status === 'suspended' ? { suspendedAt: Date.now() } : {}),
    ...(status === 'approved'
      ? { approvedName: x.name, lastApproved: snapshot(x), statusReason: FieldValue.delete(), editRejectedReason: FieldValue.delete() }
      : { statusReason: reason }),
  });
  await require('./sanctions').onStatusChange(kind, x, prev, status);
  const title = status === 'approved' ? [`Your ${en} is now listed`, `আপনার ${bn} তালিকায় এসেছে`]
    : status === 'suspended' ? [`Your ${en} was suspended. You can appeal from Profile → Help & appeals.`, `আপনার ${bn} বন্ধ করা হয়েছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।`]
      : [`${en[0].toUpperCase()}${en.slice(1)} not approved`, `${bn} অনুমোদন হয়নি`];
  await tellOwner(x.ownerUid, title, withReason, kind);
  return { ok: true };
}

/** রিপোর্টের পর বন্ধ হলে মালিককে কারণসহ জানানো */
async function tellSuspendedByReport(kind, x, reason) {
  const [en, bn] = LABEL[kind] || LABEL.ambulance;
  await tellOwner(x.ownerUid, [`Your ${en} was suspended after a report. You can appeal from Profile → Help & appeals.`, `রিপোর্টের পর আপনার ${bn} বন্ধ করা হয়েছে। প্রোফাইলের "সাহায্য ও আপিল" থেকে আপিল করতে পারেন।`],
    (lang) => [x.name, reason ? R.L(lang, `Reason: ${reason}`, `কারণ: ${reason}`) : ''].filter(Boolean).join(' · '), kind);
}

/** প্যানেলের অপেক্ষার তালিকায়: একই ফোন/গাড়ি/লাইসেন্স আগে থেকে আছে কি না */
async function possibleDuplicates(collection, id, x) {
  const keys = [['phone', x.phone], ['vehicleNo', x.vehicleNo], ['licenseNo', x.licenseNo]].filter(([, v]) => typeof v === 'string' && v.trim());
  const found = new Map();
  for (const [field, value] of keys) {
    const snap = await db.collection(collection).where(field, '==', value).limit(5).get();
    snap.docs.filter((d) => d.id !== id).forEach((d) => found.set(d.id, { id: d.id, name: d.get('name') || '', status: d.get('status') || '', field }));
  }
  return [...found.values()].slice(0, 5);
}

module.exports = { snapshot, changedFields, resolveListing, tellSuspendedByReport, possibleDuplicates };
