// পরীক্ষার ঘড়ি: ঢাকার (UTC+6) নির্দিষ্ট মুহূর্ত থেকে চলতে থাকা Date — যখনই চালান, পরীক্ষা একই সময়ে চলে।
// ডেমোর খোলা-বন্ধের সময় (চেম্বার, দোকান) দিনের সময়ের ওপর নির্ভর করে; রাতে চালালে আগে কিছু পরীক্ষা ভাঙত।
// ব্যবহার: const clock = require('./support/clock'); clock.setDhaka('2026-10-19 23:50'); … clock.reset();
'use strict';
const RealDate = Date;
let off = 0;
class TestDate extends RealDate {
  constructor(...a) {
    if (a.length === 0) super(RealDate.now() + off);
    else super(...a);
  }
  static now() {
    return RealDate.now() + off;
  }
}
globalThis.Date = TestDate;

/** "YYYY-MM-DD HH:MM" ঢাকার সময় → এখন থেকে ঘড়ি সেখান থেকে চলে */
function setDhaka(s) {
  const t = RealDate.parse(`${s.replace(' ', 'T')}:00+06:00`);
  if (Number.isNaN(t)) throw new Error(`bad Dhaka time: ${s}`);
  off = t - RealDate.now();
  return t;
}
function reset() {
  off = 0;
}
/** ঘড়ি ms এগিয়ে দেওয়া (অ্যাম্বুলেন্স ডাকার ৬০ সেকেন্ড/৩ মিনিট) */
function advance(ms) {
  off += ms;
}
module.exports = { setDhaka, reset, advance, RealDate };
