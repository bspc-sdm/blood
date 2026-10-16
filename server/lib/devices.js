// এক অ্যাকাউন্ট, কয়েকটা ফোন: প্রতিটা ফোনের পুশ টোকেন আলাদা রাখা (users.fcmTokens, সর্বোচ্চ ৫টা)।
// এক ফোনে লগআউট করলে শুধু সেই ফোনের টোকেন যায়, অন্য ফোনে নোটিফিকেশন চলতে থাকে।
// একই ফোনে অন্য অ্যাকাউন্টে ঢুকলে আগের অ্যাকাউন্ট থেকে টোকেনটা সরে যায় (আগের জনের নোটিফিকেশন এই ফোনে আর আসে না)।
const { db, FieldValue, HttpError } = require('./firebase');
const C = require('./community');

const MAX_DEVICES = 5;
const validToken = (t) => typeof t === 'string' && t.length >= 20 && t.length <= 4096 && !/\s/.test(t);

/** অন্য অ্যাকাউন্টে এই টোকেন থাকলে সরানো */
async function detachFromOthers(token, keepUid) {
  const [inList, single] = await Promise.all([
    db.collection('users').where('fcmTokens', 'array-contains', token).limit(10).get(),
    db.collection('users').where('fcmToken', '==', token).limit(10).get(),
  ]);
  const others = new Map([...inList.docs, ...single.docs].filter((d) => d.id !== keepUid).map((d) => [d.id, d]));
  await Promise.all([...others.values()].map((d) => d.ref.update({
    fcmTokens: FieldValue.arrayRemove(token),
    ...(d.get('fcmToken') === token ? { fcmToken: FieldValue.delete() } : {}),
  }).catch(() => {})));
}

const actions = {
  /** অ্যাপ খুললে বা টোকেন বদলালে: এই ফোনকে এই অ্যাকাউন্টে যোগ */
  async device_register(req, user) {
    const token = req.body?.token;
    if (!validToken(token)) throw new HttpError(400, C.tr(req, 'Invalid device', 'ফোন চেনা যায়নি'));
    await detachFromOthers(token, user.uid);
    const ref = db.doc(`users/${user.uid}`);
    await db.runTransaction(async (t) => {
      const u = await t.get(ref);
      const list = ((u.exists && u.get('fcmTokens')) || []).filter((x) => x !== token);
      // পুরোনো অ্যাপের একক টোকেনও তালিকায় নিয়ে আসা
      const legacy = u.exists && u.get('fcmToken');
      if (legacy && legacy !== token && !list.includes(legacy)) list.push(legacy);
      t.set(ref, { fcmTokens: [...list, token].slice(-MAX_DEVICES), fcmToken: token }, { merge: true });
    });
    return { ok: true };
  },

  /** লগআউট: শুধু এই ফোনের টোকেন সরানো */
  async device_unregister(req, user) {
    const token = req.body?.token;
    if (!validToken(token)) return { ok: true };
    const ref = db.doc(`users/${user.uid}`);
    await db.runTransaction(async (t) => {
      const u = await t.get(ref);
      if (!u.exists) return;
      const rest = (u.get('fcmTokens') || []).filter((x) => x !== token);
      t.update(ref, {
        fcmTokens: rest,
        // পুরোনো ঘরে এই ফোনটা থাকলে অন্য একটা চালু ফোন বসে (না থাকলে মুছে যায়)
        ...(u.get('fcmToken') === token ? { fcmToken: rest.length ? rest[rest.length - 1] : FieldValue.delete() } : {}),
      });
    });
    return { ok: true };
  },
};

module.exports = { actions, detachFromOthers };
