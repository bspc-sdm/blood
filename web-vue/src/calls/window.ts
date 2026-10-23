// কখন অ্যাপে কল করা যায় (বোতাম দেখানোর জন্য; শেষ কথা সার্ভারের) — backend/docs/CALLS.md-এর সম্পর্ক
const DHAKA_MS = 6 * 3600_000;

/** অ্যাপয়েন্টমেন্ট: নিশ্চিত, আর সময়ের ৩০ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত (সময় লেখা না থাকলে সেদিন সারাদিন)।
 *  date = বাংলাদেশের দিনের UTC মধ্যরাত, start = "HH:MM" */
export function apptCallOpen(a: { date: number; start: string; status: string }, now = Date.now()): boolean {
  if (a.status !== 'confirmed' || !a.date) return false;
  const m = /^(\d{1,2}):(\d{2})/.exec(a.start || '');
  const dayStart = a.date - DHAKA_MS;
  if (!m) return now >= dayStart && now < dayStart + 24 * 3600_000;
  const slot = dayStart + (Number(m[1]) * 60 + Number(m[2])) * 60_000;
  return now >= slot - 30 * 60_000 && now <= slot + 3 * 3600_000;
}
