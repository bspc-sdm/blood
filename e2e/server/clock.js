// পরীক্ষার ঘড়ি (node --require): E2E_TIME_OFFSET_MS মিলিসেকেন্ড এগিয়ে/পিছিয়ে চলা Date।
// Auth emulator আর লোকাল সার্ভার দুটোতেই একই সরানো সময় — যাতে রাত ১টায় চালালেও চেম্বারের সকাল ১১টার মতো চলে
// (সিরিয়াল, আজকের চেম্বার, "আজ" — দিনের সময়ের ওপর নির্ভর করে), আর লগইন টোকেনের সময় দুদিকে মেলে।
'use strict';
const off = Number(process.env.E2E_TIME_OFFSET_MS || 0);
if (off) {
  const RealDate = Date;
  class ShiftedDate extends RealDate {
    constructor(...a) {
      if (a.length === 0) super(RealDate.now() + off);
      else super(...a);
    }
    static now() {
      return RealDate.now() + off;
    }
  }
  globalThis.Date = ShiftedDate;
}
