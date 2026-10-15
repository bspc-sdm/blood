// ফাঁক সারানোর নিয়ম (২.৮): নামের ছোট বানান-ঠিক, নিষেধাজ্ঞার নম্বর একরূপ করা, উপজেলা-ভিত্তিক বাসায় সেবা
const test = require('node:test');
const assert = require('node:assert');
const K = require('../lib/care');

test('small name edits skip review, big ones do not', () => {
  assert.equal(K.smallNameEdit('Rahim Ambulance', 'Rahim Ambulence'), true);
  assert.equal(K.smallNameEdit('Rahim Ambulance', 'rahim  ambulance'), true);
  assert.equal(K.smallNameEdit('রহিম অ্যাম্বুলেন্স', 'রহিম এম্বুলেন্স'), true);
  assert.equal(K.smallNameEdit('Rahim Ambulance', 'Karim Transport'), false);
  assert.equal(K.smallNameEdit('Rahim', 'Rahim Brothers'), false);
  assert.equal(K.smallNameEdit(null, 'Rahim'), false);
});

test('vehicle and licence numbers normalise so spelling tricks do not dodge a ban', () => {
  assert.equal(K.normId('DHAKA METRO-CHA 11-2233'), K.normId('Dhaka Metro Cha 112233'));
  assert.equal(K.normId('ঢাকা মেট্রো-চ ১১-২২৩৩'), K.normId('ঢাকামেট্রোচ 11 2233'));
});

test('review needed: name typo on approved listing keeps it listed', () => {
  const snap = (x) => ({ get: (k) => x[k] });
  const s = snap({ status: 'approved', name: 'Rahim Ambulance', phone: '01711000000', vehicleNo: 'X-1' });
  assert.deepEqual(K.reviewNeeded(s, { name: 'Rahim Ambulence', phone: '01711000000', vehicleNo: 'X-1' }, ['phone', 'vehicleNo']).changed, false);
  assert.equal(K.reviewNeeded(s, { name: 'Rahim Ambulance', phone: '01811000000', vehicleNo: 'X-1' }, ['phone', 'vehicleNo']).changed, true);
  // ধাপে ধাপে বদলে পুরো নাম পাল্টানো যায় না: শেষ অনুমোদিত নামের সাথে তুলনা
  const s2 = snap({ status: 'approved', approvedName: 'Rahim Ambulance', name: 'Rahim Ambulxx', phone: '01711000000', vehicleNo: 'X-1' });
  assert.equal(K.reviewNeeded(s2, { name: 'Rahim Ambxxxx', phone: '01711000000', vehicleNo: 'X-1' }, ['phone', 'vehicleNo']).changed, true);
});

test('home care near me also finds upazila-only providers in the same district', () => {
  const { UPAZILA } = require('../lib/hospital-match');
  const [id, u] = [...UPAZILA.entries()][0];
  const p = { homeVisit: { enabled: true, upazilaIds: [id], radiusKm: 5 } };
  assert.ok(K.homeMatch(p, { lat: 23.8, lng: 90.4, district: u.district }));
  assert.equal(K.homeMatch(p, { lat: 23.8, lng: 90.4, district: 'অন্য জেলা' }), null);
});

const R = require('../lib/rules');

test('request stays active 6h after the needed time, or until day end without a time', () => {
  const day = Date.UTC(2026, 9, 20); // ২০ অক্টোবর, UTC মধ্যরাত
  const at = day - 6 * 3600e3 + 9 * 3600e3; // ঢাকায় সকাল ৯টা
  const r = { status: 'open', neededByMillis: day, neededAtMillis: at };
  assert.equal(R.isActive(r, at + 5 * 3600e3), true);
  assert.equal(R.isActive(r, at + 7 * 3600e3), false);
  const noTime = { status: 'open', neededByMillis: day };
  assert.equal(R.isActive(noTime, day + 20 * 3600e3), true);
  assert.equal(R.isActive(noTime, day + 25 * 3600e3), false);
});

test('bags left counts confirmed donors or bags received, whichever is more', () => {
  assert.equal(R.bagsLeft({ bags: 3, confirmedCount: 1, bagsReceived: 0 }), 2);
  assert.equal(R.bagsLeft({ bags: 3, confirmedCount: 1, bagsReceived: 2 }), 1);
  assert.equal(R.bagsLeft({ bags: 2, confirmedCount: 3 }), 0);
});
