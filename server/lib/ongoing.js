// হোমের "চলমান": কোনো সার্ভিসে আবেদন/অনুরোধের পর কাজ শেষ হওয়া পর্যন্ত প্রতিটা ধাপ এক জায়গায়।
// শেষ হলে (অনুমোদিত, নিয়োগ, বাতিল, মেয়াদ শেষ) আর দেখায় না। প্রতিটা কার্ডে চাপলে সেই সার্ভিসের পাতা খোলে
// (open = নোটিফিকেশনের মতো data, অ্যাপের openFromData দিয়েই খোলে)।
const { db } = require('./firebase');
const R = require('./rules');
const C = require('./community');

const LISTING_NAME = { pharmacy: ['Pharmacy', 'ফার্মেসি'], ambulance: ['Ambulance', 'অ্যাম্বুলেন্স'], bloodbank: ['Blood bank', 'ব্লাড ব্যাংক'] };

/** নিজের তালিকা (ফার্মেসি/অ্যাম্বুলেন্স/ব্লাড ব্যাংক): অনুমোদনের অপেক্ষা, ছবি চাওয়া, বাতিল/বন্ধ */
function listingItem(req, kind, d) {
  const x = d.data();
  const [en, bn] = LISTING_NAME[kind];
  const title = `${C.tr(req, en, bn)}: ${x.name || ''}`;
  const open = { type: 'services', kind };
  const at = x.updatedAt || x.createdAt || 0;
  if (x.docsRequestedAt) {
    return { key: `${kind}_${d.id}`, service: kind, title, line: C.tr(req, 'The Dorkar team asked for document photos', 'Dorkar টিম কাগজের ছবি চেয়েছে'), note: x.docsRequestNote || '', action: true, at, open };
  }
  if (x.status === 'pending') return { key: `${kind}_${d.id}`, service: kind, title, line: C.tr(req, 'Waiting for approval', 'অনুমোদনের অপেক্ষায়'), note: '', action: false, at, open };
  if (x.status === 'rejected' || x.status === 'suspended') {
    return {
      key: `${kind}_${d.id}`, service: kind, title,
      line: x.status === 'rejected' ? C.tr(req, 'Not approved', 'অনুমোদন হয়নি') : C.tr(req, 'Suspended by the Dorkar team', 'Dorkar টিম বন্ধ করেছে'),
      note: x.statusReason || '', action: true, at, open,
    };
  }
  return null;
}

async function ongoing(req, uid) {
  const now = Date.now();
  const today = R.todayDhakaUtcMidnight(now);
  const [pharm, ambs, banks, badge, finds, apPend, apConf, provPend] = await Promise.all([
    db.doc(`pharmacies/${uid}`).get(),
    db.collection('ambulances').where('ownerUid', '==', uid).limit(10).get(),
    db.collection('bloodBanks').where('ownerUid', '==', uid).limit(10).get(),
    db.doc(`badges/${uid}`).get(),
    db.collection('medFinds').where('uid', '==', uid).limit(30).get(),
    db.collection('appointments').where('patientUid', '==', uid).where('status', '==', 'pending').limit(50).get(),
    db.collection('appointments').where('patientUid', '==', uid).where('status', '==', 'confirmed').limit(50).get(),
    db.collection('appointments').where('providerUid', '==', uid).where('status', '==', 'pending').limit(100).get(),
  ]);
  const items = [];
  if (pharm.exists) items.push(listingItem(req, 'pharmacy', pharm));
  ambs.docs.forEach((d) => items.push(listingItem(req, 'ambulance', d)));
  banks.docs.forEach((d) => items.push(listingItem(req, 'bloodbank', d)));

  // পেশাদার ব্যাজ (ডাক্তার/নার্স)
  if (badge.exists) {
    const b = badge.data();
    const title = C.tr(req, 'Professional badge', 'পেশাদার ব্যাজ');
    const open = { type: 'badge' };
    const at = b.updatedAt || b.createdAt || 0;
    if (b.docsRequestedAt) items.push({ key: 'badge', service: 'badge', title, line: C.tr(req, 'The Dorkar team asked for document photos', 'Dorkar টিম কাগজের ছবি চেয়েছে'), note: b.docsRequestNote || '', action: true, at, open });
    else if (b.status === 'pending') items.push({ key: 'badge', service: 'badge', title, line: C.tr(req, 'Under review', 'যাচাই চলছে'), note: '', action: false, at, open });
    else if (b.pendingChange) items.push({ key: 'badge', service: 'badge', title, line: C.tr(req, 'Correction under review', 'সংশোধন যাচাই চলছে'), note: '', action: false, at, open });
    else if (b.status === 'rejected') items.push({ key: 'badge', service: 'badge', title, line: C.tr(req, 'Not approved', 'অনুমোদন হয়নি'), note: b.statusReason || '', action: true, at, open });
  }

  // ওষুধ খোঁজা: খোলা থাকা পর্যন্ত
  finds.docs.filter((d) => d.get('status') === 'open' && now <= (d.get('expiresAt') || 0)).forEach((d) => {
    const x = d.data();
    const yes = Object.values(x.answers || {}).filter((a) => a && a.have).length;
    items.push({
      key: `find_${d.id}`, service: 'medfind',
      title: `${C.tr(req, 'Finding medicine', 'ওষুধ খোঁজা')}: ${(x.drug && x.drug.name) || ''}`,
      line: yes ? C.tr(req, `${yes} pharmacy said they have it`, `${yes}টা ফার্মেসি বলেছে আছে`) : C.tr(req, 'Waiting for pharmacies to answer', 'ফার্মেসির উত্তরের অপেক্ষায়'),
      note: '', action: yes > 0, at: x.createdAt || 0, open: { type: 'med_find_mine', findId: d.id },
    });
  });

  // অ্যাপয়েন্টমেন্ট (রোগী): আজ বা সামনের দিনের অপেক্ষায়/নিশ্চিত
  [...apPend.docs, ...apConf.docs].filter((d) => (d.get('date') || 0) >= today).forEach((d) => {
    const x = d.data();
    items.push({
      key: `appt_${d.id}`, service: 'appointment',
      title: `${C.tr(req, 'Appointment', 'অ্যাপয়েন্টমেন্ট')}: ${x.providerName || ''}`,
      line: x.status === 'confirmed' ? C.tr(req, 'Confirmed', 'নিশ্চিত হয়েছে') : C.tr(req, 'Waiting for confirmation', 'নিশ্চিতের অপেক্ষায়'),
      note: [x.chamberBn || x.chamber || '', x.serial ? C.tr(req, `Serial ${x.serial}`, `সিরিয়াল ${x.serial}`) : ''].filter(Boolean).join(' · '),
      date: x.date || 0, action: false, at: x.createdAt || 0, open: { type: 'appointment' },
    });
  });
  // অ্যাপয়েন্টমেন্ট (ডাক্তার/নার্স): নতুন অনুরোধ
  const newReq = provPend.docs.filter((d) => (d.get('date') || 0) >= today);
  if (newReq.length) {
    items.push({
      key: 'appt_requests', service: 'appointment',
      title: C.tr(req, 'Appointment requests', 'অ্যাপয়েন্টমেন্টের অনুরোধ'),
      line: C.tr(req, `${newReq.length} waiting for your answer`, `${newReq.length}টা আপনার উত্তরের অপেক্ষায়`),
      note: '', action: true, at: Math.max(...newReq.map((d) => d.get('createdAt') || 0)), open: { type: 'appointment' },
    });
  }

  // চাকরি — FEATURE:jobs (মুছলে এই অংশ সরান)
  try {
    items.push(...(await require('./jobs').ongoing(req, uid, now)));
  } catch (e) {
    console.error('job ongoing failed', e.message);
  }

  // যেগুলোতে কিছু করতে হবে আগে, তারপর নতুনগুলো
  return items.filter(Boolean).sort((a, b) => (b.action - a.action) || (b.at - a.at)).slice(0, 20);
}

module.exports = {
  actions: {
    async home_ongoing(req, user) {
      return { items: await ongoing(req, user.uid) };
    },
  },
};
