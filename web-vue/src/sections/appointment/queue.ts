// সিরিয়াল চলছে (সার্ভারের server/lib/queue.js; অ্যাপের Queue.kt):
//  - PatientQueue: রোগী (বা কেয়ারগিভার) — আজকের নিশ্চিত সিরিয়ালে এখন কত চলছে, আগে কতজন, কখন আসবেন, "দেরি হবে"
//  - QueuePanel: ডাক্তার/সহকারী — "শুরু করুন", "পরের রোগী", সিরিয়াল চেপে "এখন দেখছি", "বিরতি", দেরির উত্তর
// খোলা থাকলে ৩০ সেকেন্ডে একবার নতুন করে আনে (রিয়েলটাইম নয়)।
// "পরের রোগী"/"এখন দেখছি"-তে এখন দেখা নম্বর (expect) আর সারির দিন (date) যায় — অন্য কেউ এইমাত্র এগিয়ে থাকলে সার্ভার কিছু করে না
// (stale), তখন শুধু নতুন করে আনা। মাঝরাত পেরোনো চেম্বারে ভোর ৬টা পর্যন্ত গতকালের সারি চলে।
// Vue: এখানে শুধু হিসাব; কার্ড/প্যানেল PatientQueue.vue, PatientQueueLine.vue, QueuePanel.vue-তে
import { toValue, type MaybeRefOrGetter } from 'vue';
import { useCall } from '@/lib/query';
import { formatTime } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { DAY_MS, today, type Appointment } from './model';

export const REFRESH = 30_000;
export const LATE_MINUTES = [10, 20, 30, 60];

export interface LateInfo { min: number; note: string; at: number; status: 'asked' | 'ok' | 'later' }
export interface PatientQueueInfo {
  live: boolean;
  serial?: number;
  started?: boolean;
  paused?: boolean;
  current?: number;
  state?: 'waiting' | 'now' | 'done' | 'passed' | 'finished';
  ahead?: number;
  later?: boolean;
  avgMin?: number;
  eta?: number | null;
  arriveBy?: number | null;
  late?: LateInfo | null;
  canLate?: boolean;
}
export interface QueueRow { id: string; serial: number; name: string; status: string; state: string; ahead: number; late: LateInfo | null }
export interface QueueChamber {
  /** কোন দিনের সারি (মাঝরাত পেরোলে গতকালের) */
  date?: number;
  chamberKey: string; key: string; name: string; nameBn: string; start: string; avgMin: number; avgFrom: string; lateAsked: number;
  queue: { started: boolean; current: number; paused: boolean; finished: boolean; next: number | null; left: number; max: number };
  rows: QueueRow[];
}

/** আজকের নিশ্চিত সিরিয়াল কি না (সারির তথ্য তখনই); ভোর ৬টার আগে গতকালেরটাও (চেম্বার মাঝরাত পেরোলে — সার্ভার ঠিক করে চলছে কি না) */
export const queueLive = (a: Appointment) => a.role === 'patient' && a.status === 'confirmed' && a.serial > 0
  && (a.date === today() || (a.date === today() - DAY_MS && new Date().getHours() < 6));
export const lateText = (min: number) => tr(`${min} min late`, `${num(min)} মিনিট দেরি হবে`);

export function usePatientQueue(a: MaybeRefOrGetter<Appointment>) {
  return useCall<PatientQueueInfo>('appointment_queue', () => ({ id: toValue(a).id }), () => ({ enabled: queueLive(toValue(a)), refetchInterval: REFRESH, staleTime: 15_000 }));
}

/** "১১:০০-এর মধ্যে উপস্থিত থাকুন" / "এখনই চলে আসুন" */
export function arriveText(q: PatientQueueInfo) {
  if (!q.arriveBy) return null;
  return q.arriveBy <= Date.now() ? tr('Come now', 'এখনই চলে আসুন') : tr(`Be there by ${formatTime(q.arriveBy)}`, `${formatTime(q.arriveBy)}-এর মধ্যে উপস্থিত থাকুন`);
}
export function stateText(q: PatientQueueInfo): string | null {
  switch (q.state) {
    case 'now': return tr("It's your turn now", 'এখন আপনার পালা');
    case 'done': return tr('Seen', 'দেখা হয়ে গেছে');
    case 'passed': return tr('Your serial has passed — tell the chamber', 'আপনার সিরিয়াল পার হয়ে গেছে — চেম্বারে জানান');
    case 'finished': return tr("Today's serials are over", 'আজকের সিরিয়াল শেষ');
    default: return null;
  }
}

