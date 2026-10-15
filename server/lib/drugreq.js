// সরকারি তালিকায় নেই এমন ওষুধ: দোকান যোগের অনুরোধ করে, অ্যাডমিন যাচাই করে অনুমোদন দেন।
// অনুমোদিত ওষুধ customDrugs/{id} — সবার অ্যাপে খোঁজায় আসে (key "c_<id>"), আর অনুরোধকারী দোকানের তালিকায় যোগ হয়।
const { db, HttpError, sendPush } = require('./firebase');
const C = require('./community');
const R = require('./rules');
const { useQuota } = require('./community-db');
const stock = require('./stock');

const FORMS_MAX = 40;
let cache = { at: 0, items: null };

function clean(v, max) {
  return typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, max) : '';
}

async function requireAdmin(req, user) {
  if (!(await require('./admin').isAdmin(user))) throw new HttpError(403, C.tr(req, 'Admins only', 'শুধু অ্যাডমিন'));
}

function view(d) {
  const x = d.data();
  return {
    id: d.id,
    name: x.name,
    generic: x.generic || '',
    strength: x.strength || '',
    form: x.form || '',
    company: x.company || '',
    status: x.status,
    note: x.note || '',
    createdAt: x.createdAt,
  };
}

const actions = {
  /** দোকান: তালিকার বাইরের ওষুধ যোগের অনুরোধ */
  async drug_request(req, user) {
    const shop = await db.doc(`pharmacies/${user.uid}`).get();
    if (!shop.exists || shop.get('status') !== 'approved') {
      throw new HttpError(403, C.tr(req, 'Only approved pharmacies can request new medicines', 'শুধু অনুমোদিত ফার্মেসি নতুন ওষুধের অনুরোধ করতে পারে'));
    }
    const b = req.body || {};
    const name = clean(b.name, 80);
    if (name.length < 2) throw new HttpError(400, C.tr(req, 'Write the brand name', 'ব্র্যান্ডের নাম লিখুন'));
    const data = {
      pharmacyId: user.uid,
      pharmacyName: shop.get('name') || '',
      district: shop.get('district') || '',
      name,
      generic: clean(b.generic, 120),
      strength: clean(b.strength, 40),
      form: clean(b.form, FORMS_MAX),
      company: clean(b.company, 80),
      status: 'pending',
      createdAt: Date.now(),
    };
    await useQuota(req, user.uid, 'drugReq');
    await db.collection('drugRequests').add(data);
    return { ok: true };
  },

  async drug_requests_mine(req, user) {
    const snap = await db.collection('drugRequests').where('pharmacyId', '==', user.uid).limit(100).get();
    return { items: snap.docs.map(view).sort((a, b) => b.createdAt - a.createdAt).slice(0, 50) };
  },

  /** সবার জন্য: অনুমোদিত বাড়তি ওষুধ (অল্প, তাই ১০ মিনিট মনে রাখা) */
  async drugs_custom() {
    if (!cache.items || Date.now() - cache.at > 10 * 60 * 1000) {
      const snap = await db.collection('customDrugs').limit(5000).get();
      cache = {
        at: Date.now(),
        items: snap.docs.map((d) => {
          const x = d.data();
          return { key: `c_${d.id}`, name: x.name, generic: [x.generic, x.strength].filter(Boolean).join(' '), form: x.form || '', company: x.company || '' };
        }),
      };
    }
    return { items: cache.items };
  },

  // ---------- অ্যাডমিন ----------
  async admin_drug_requests(req, user) {
    await requireAdmin(req, user);
    const status = ['pending', 'approved', 'rejected'].includes(req.body?.status) ? req.body.status : 'pending';
    const snap = await db.collection('drugRequests').where('status', '==', status).limit(200).get();
    return {
      items: snap.docs
        .map((d) => ({ ...view(d), pharmacyId: d.get('pharmacyId'), pharmacyName: d.get('pharmacyName') || '', district: d.get('district') || '' }))
        .sort((a, b) => (status === 'pending' ? a.createdAt - b.createdAt : b.createdAt - a.createdAt)),
    };
  },

  /** অনুমোদন (দরকারে নাম ইত্যাদি ঠিক করে) বা বাতিল */
  async admin_drug_request_resolve(req, user) {
    await requireAdmin(req, user);
    const b = req.body || {};
    if (!R.isValidId(b.id)) throw new HttpError(400, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    const ref = db.doc(`drugRequests/${b.id}`);
    const s = await ref.get();
    if (!s.exists) throw new HttpError(404, C.tr(req, 'Not found', 'পাওয়া যায়নি'));
    if (s.get('status') !== 'pending') return { ok: true, unchanged: true };
    const approve = b.approve === true;
    const now = Date.now();
    if (approve) {
      const name = clean(b.name, 80) || s.get('name');
      const drug = {
        name,
        generic: clean(b.generic, 120) || s.get('generic') || '',
        strength: clean(b.strength, 40) || s.get('strength') || '',
        form: clean(b.form, FORMS_MAX) || s.get('form') || '',
        company: clean(b.company, 80) || s.get('company') || '',
        requestId: b.id,
        approvedBy: user.uid,
        createdAt: now,
      };
      // অনুরোধের id-ই ওষুধের id — একই অনুরোধ দুবার অনুমোদন হলেও একটাই ওষুধ
      await db.doc(`customDrugs/${b.id}`).set(drug);
      cache = { at: 0, items: null };
      await ref.update({ status: 'approved', reviewedAt: now, reviewedBy: user.uid, ...drug });
      const shop = await db.doc(`pharmacies/${s.get('pharmacyId')}`).get();
      if (shop.exists) await stock.applyChanges(shop.id, shop.get('district'), [{ key: `c_${b.id}`, s: 'in' }], true);
    } else {
      await ref.update({ status: 'rejected', reviewedAt: now, reviewedBy: user.uid, note: clean(b.note, 200) });
    }
    try {
      const u = await db.doc(`users/${s.get('pharmacyId')}`).get();
      const lang = u.get('lang');
      await sendPush([{
        uid: s.get('pharmacyId'),
        token: u.get('fcmToken'),
        title: approve ? R.L(lang, 'New medicine added', 'নতুন ওষুধ যোগ হয়েছে') : R.L(lang, 'Medicine request not approved', 'ওষুধ যোগের অনুরোধ অনুমোদন হয়নি'),
        body: s.get('name'),
        data: { type: 'pharmacy_order' },
      }]);
    } catch (e) {
      console.error('drug request push failed', e.message);
    }
    return { ok: true };
  },
};

module.exports = { actions };
