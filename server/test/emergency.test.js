const test = require('node:test');
const assert = require('node:assert');
const E = require('../lib/emergency-rules');

const NOW = Date.UTC(2026, 9, 15);
const adult = { birthDateMillis: Date.UTC(1995, 0, 1), weightKg: 60, notify: true, fcmToken: 't' };
const u = (uid, bloodGroup, lat, extra = {}) => ({ uid, bloodGroup, lat, lng: 90.4, ...adult, ...extra });

test('জরুরি আহ্বান: গ্রুপ আর ব্যাগ যাচাই', () => {
  const g = E.parseGroups({ 'A+': 2, 'B-': 0, 'X+': 5, 'O+': 999, 'AB-': 1.5 });
  assert.deepStrictEqual(Object.keys(g).sort(), ['A+', 'O+']);
  assert.strictEqual(g['O+'].need, E.MAX_NEED);
  assert.strictEqual(g['A+'].coming, 0);
  assert.strictEqual(E.waveSize(1), 5);
  assert.strictEqual(E.waveSize(4), 12);
});

test('জরুরি আহ্বান: কাছের যোগ্য ডোনার আগে, ভরা বা বন্ধ গ্রুপ বাদ, আগে জানানো বাদ', () => {
  const center = { lat: 23.8, lng: 90.4 };
  const users = [
    u('a1', 'A+', 23.81), u('a2', 'A+', 23.9), u('a3', 'A+', 23.85),
    u('far', 'A+', 25.0), // অনেক দূরে
    u('off', 'A+', 23.8, { notify: false }),
    u('recent', 'A+', 23.8, { lastDonationMillis: NOW - 10 * 86400000 }), // সদ্য রক্ত দিয়েছেন
    u('notoken', 'A+', 23.8, { fcmToken: null }),
    u('b1', 'B+', 23.8),
    u('o1', 'O+', 23.8),
  ];
  const groups = E.parseGroups({ 'A+': 1, 'B+': 1, 'O+': 1 });
  groups['O+'].closed = true;
  const wave = E.pickWave(users, center, groups, 25, new Set(['a3']), NOW);
  assert.deepStrictEqual(wave.filter((x) => x.group === 'A+').map((x) => x.uid), ['a1', 'a2']);
  assert.deepStrictEqual(wave.filter((x) => x.group === 'B+').map((x) => x.uid), ['b1']);
  assert.ok(!wave.some((x) => x.group === 'O+'));
  // যথেষ্ট আসছেন → আর কাউকে না
  groups['A+'].coming = 1;
  assert.ok(!E.pickWave(users, center, groups, 25, new Set(), NOW).some((x) => x.group === 'A+'));
});

test('জরুরি আহ্বান: চালু থাকা', () => {
  assert.ok(E.isLive({ status: 'active', endsAt: NOW + 1 }, NOW));
  assert.ok(!E.isLive({ status: 'active', endsAt: NOW - 1 }, NOW));
  assert.ok(!E.isLive({ status: 'closed', endsAt: NOW + 1 }, NOW));
});

test('জরুরি আহ্বান: কয়েকটা ফোন থাকলে সবগুলোতে যায় (নতুন fcmTokens)', () => {
  const center = { lat: 23.8, lng: 90.4 };
  const users = [
    u('multi', 'A+', 23.8, { fcmToken: 't2', fcmTokens: ['t1', 't2'] }),
    u('onlyList', 'A+', 23.81, { fcmToken: null, fcmTokens: ['t3'] }),
  ];
  const wave = E.pickWave(users, center, E.parseGroups({ 'A+': 2 }), 25, new Set(), NOW);
  assert.deepStrictEqual(wave.map((x) => x.uid), ['multi', 'onlyList']);
  assert.deepStrictEqual(wave[0].tokens, ['t1', 't2']);
  assert.deepStrictEqual(wave[1].tokens, ['t3']);
});
