// Health Discussion (প্রশ্ন-উত্তর), Health Tips আর পেশাদার ব্যাজের নিয়ম — আলাদা রাখা হয়েছে যাতে টেস্ট করা যায়।

const BADGES = ['doctor', 'nurse', 'nutritionist', 'trainer', 'expert'];
const LIMITS = { ask: 5, answer: 40, tip: 5, badge: 3, book: 5, report: 20, hospital: 10, homevisit: 5, homevisitTry: 20, phoneCode: 5, ambulance: 10, pharmacy: 5, medTry: 30, medOrder: 10, ticket: 5, medFind: 5, bloodbank: 5, dateChange: 3, docs: 10, job: 10, apply: 30 }; // দিনে সর্বোচ্চ
const PAGE = 30;

function cleanText(v, max) {
  if (typeof v !== 'string') return null;
  const t = v.replace(/\r\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  if (!t || t.length > max) return null;
  return t;
}

/** ভাষা: অ্যাপ body.lang পাঠায় ("en" / "bn") */
function tr(req, en, bn) {
  return req.body?.lang === 'bn' ? bn : en;
}

function isBadge(v) {
  return BADGES.includes(v);
}

module.exports = { BADGES, LIMITS, PAGE, cleanText, tr, isBadge };
