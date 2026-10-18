// লেখা ছোট করা — s.slice(0, n)-এর বদলে।
// slice UTF-16-এর একক ধরে কাটে, তাই ইমোজির মতো দুই-এককের অক্ষরের মাঝখানে কেটে অর্ধেকটা (একা surrogate) রেখে দিতে পারে:
// PostgreSQL সেটা রাখে না (22P02), Firestore '?'-এর মতো U+FFFD বসায়। clip অক্ষরের (code point) সীমায় কাটে:
// সর্বোচ্চ n একক (slice-এর মতোই সীমা), কিন্তু শেষ অক্ষরটা অর্ধেক হলে সেটা পুরো বাদ।

/** s-এর প্রথম সর্বোচ্চ n UTF-16 একক, কোনো অক্ষর না ভেঙে (s লেখা না হলে String(s)) */
function clip(s, n) {
  const str = typeof s === 'string' ? s : String(s);
  if (str.length <= n) return str;
  let end = Math.max(0, n);
  if (end > 0) {
    const c = str.charCodeAt(end - 1);
    if (c >= 0xd800 && c <= 0xdbff) end--; // high surrogate: জোড়ার বাকি অর্ধেক কাটা পড়ছে
  }
  return str.slice(0, end);
}

module.exports = { clip };
