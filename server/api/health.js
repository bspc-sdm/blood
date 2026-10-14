// সার্ভার চালু আছে কি না দেখার জন্য। Firebase-এর সাথে সংযোগও যাচাই করে।
module.exports = async (req, res) => {
  try {
    const { db } = require('../lib/firebase');
    await db.collection('serverState').limit(1).get();
    res.status(200).json({ ok: true, firebase: true });
  } catch (e) {
    console.error(e);
    res.status(500).json({ ok: false });
  }
};
