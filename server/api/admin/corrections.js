// অ্যাডমিনের জন্য: অপেক্ষায় থাকা "শেষ রক্তদানের তারিখ সংশোধন" অনুরোধের তালিকা
const { handler } = require('../../lib/firebase');
const { verifyAdmin } = require('../../lib/admin');
const { pendingCorrections } = require('../../lib/corrections');

module.exports = handler(async (req, res) => {
  await verifyAdmin(req, res);
  return { ok: true, items: await pendingCorrections() };
});
