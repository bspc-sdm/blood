// ছোট সময়ের জন্য মেমোরিতে রাখা (একই সার্ভার-চালু থাকা পর্যন্ত): একই তালিকা বারবার খোলায় ডাটাবেস বারবার পড়া হয় না।
// বদল দেখাতে সর্বোচ্চ ttl পর্যন্ত দেরি হতে পারে।
const store = new Map();

async function cached(key, ttlMs, load) {
  const now = Date.now();
  const hit = store.get(key);
  if (hit && now - hit.at < ttlMs) return hit.value;
  // একসাথে কয়েকটা অনুরোধ এলে একবারই পড়া
  if (hit && hit.pending) return hit.pending;
  // পড়ার মাঝে clear() হলে পুরোনো পড়াটা আর রাখা হয় না (নইলে মোছার পরও পুরোনো তথ্য ttl পর্যন্ত থেকে যেত)
  const pending = load().then((value) => {
    if (store.get(key)?.pending === pending) store.set(key, { at: Date.now(), value });
    return value;
  }, (e) => {
    if (store.get(key)?.pending === pending) store.delete(key);
    throw e;
  });
  store.set(key, { ...(hit || { at: 0 }), pending });
  return pending;
}

/** ডাটাবেসের ডকের মতো ব্যবহার করা যায় এমন হালকা কপি (মেমোরিতে রাখার জন্য) */
function docLike(id, x) {
  return {
    id,
    exists: true,
    data: () => x,
    get: (path) => String(path).split('.').reduce((o, k) => (o == null ? undefined : o[k]), x),
  };
}

function clear(prefix = '') {
  [...store.keys()].filter((k) => k.startsWith(prefix)).forEach((k) => store.delete(k));
}

module.exports = { cached, docLike, clear };
