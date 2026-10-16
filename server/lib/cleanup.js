// এক-বারের পরিষ্কার: ২.১৪–২.১৬-এর (চাকরি, সিভি) ফেরত নেওয়া ফিচারের রেখে যাওয়া ডেটা মোছা।
// দৈনিক ক্রনে চলে; সব মুছে গেলে config/cleanup-এ "jobsRemoved" লিখে রাখে, তারপর আর কিছু পড়ে না।
const { db, FieldValue } = require('./firebase');

const FLAG = 'jobsRemoved';
const BUDGET = 4000; // এক দিনে সর্বোচ্চ এতগুলো মোছা (ফ্রি সীমার ভেতরে); বাকি থাকলে পরদিন আবার
const JOB_TYPES = ['job', 'job_alert', 'job_applicants', 'job_mine'];

/** কোয়েরির সব ডক মোছা (বা update), বাজেট পর্যন্ত; ফেরত: কতগুলো, আর সব শেষ হলো কি না */
async function drain(query, budget, change = null) {
  let n = 0;
  while (n < budget) {
    const want = Math.min(400, budget - n);
    const snap = await query.limit(want).get();
    if (snap.empty) return { n, done: true };
    const batch = db.batch();
    snap.docs.forEach((d) => (change ? batch.update(d.ref, change) : batch.delete(d.ref)));
    await batch.commit();
    n += snap.size;
    if (snap.size < want) return { n, done: true };
  }
  return { n, done: false };
}

async function cleanupRemovedFeatures() {
  const flagRef = db.doc('config/cleanup');
  const flag = await flagRef.get();
  if (flag.exists && flag.get(FLAG)) return null;

  let left = BUDGET;
  let allDone = true;
  const counts = {};
  const run = async (name, query, change) => {
    if (left <= 0) {
      allDone = false;
      return;
    }
    const r = await drain(query, left, change);
    counts[name] = r.n;
    left -= r.n;
    if (!r.done) allDone = false;
  };

  for (const c of ['applications', 'jobs', 'employers', 'jobAlerts', 'jobSaves', 'profiles']) await run(c, db.collection(c));
  await run('verifyDocs', db.collection('verifyDocs').where('kind', 'in', ['employer', 'job']));
  await run('modReports', db.collection('modReports').where('kind', '==', 'job'));
  await run('inbox', db.collection('inbox').where('data.type', 'in', JOB_TYPES));
  // প্রোফাইলের "Dorkar দিয়ে কতবার রক্তদান" গোনা
  await run('donationCount', db.collection('serverState').where('donationCount', '>', 0), {
    donationCount: FieldValue.delete(), donationCountedDay: FieldValue.delete(),
  });

  if (allDone) {
    await Promise.all(['config/jobRules'].map((p) => db.doc(p).delete().catch(() => {})));
    await flagRef.set({ [FLAG]: Date.now() }, { merge: true });
  }
  return { done: allDone, counts };
}

/**
 * এক-বারের: নার্সের চেম্বার বন্ধ (নার্স শুধু বাসায় সেবা) — চেম্বার মুছে, চেম্বারের তালিকা আর অ্যাপয়েন্টমেন্ট বন্ধ,
 * সামনের চালু অ্যাপয়েন্টমেন্ট বাতিল (রোগীরা নোটিফিকেশন পান)। শেষে config/cleanup.nurseChambers।
 */
async function closeNurseChambers() {
  const flagRef = db.doc('config/cleanup');
  const flag = await flagRef.get();
  if (flag.exists && flag.get('nurseChambers')) return null;
  const snap = await db.collection('providers').where('type', '==', 'nurse').limit(1000).get();
  let closed = 0;
  let cancelled = 0;
  for (const d of snap.docs) {
    const hasChambers = (Array.isArray(d.get('chambers')) && d.get('chambers').length) || d.get('chamber');
    if (!hasChambers && d.get('listed') !== true && d.get('appointments') !== true && d.get('active') !== true) continue;
    await d.ref.update({ chambers: [], chamber: FieldValue.delete(), listed: false, appointments: false, active: false });
    closed++;
    cancelled += (await require('./apptcancel').cancelStale(d.id, () => false, ['Nurses now offer home care only', 'নার্সরা এখন শুধু বাসায় সেবা দেন'])) || 0;
  }
  if (snap.size < 1000) await flagRef.set({ nurseChambers: Date.now() }, { merge: true });
  return { closed, cancelled };
}

module.exports = { cleanupRemovedFeatures, closeNurseChambers };
