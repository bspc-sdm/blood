// PaySheet-এর আগের পথ (অনলাইন চেম্বারের ফি, pay request ছাড়া — appt_pay_claim): শুধু bKash, TrxID ঐচ্ছিক, শেষ ৪ অঙ্ক নেই,
// লেখা আর অবস্থার নাম চেম্বারের। নতুন পথে (pay request) এসব লাগে না — PaySheet নিজের নিয়মে চলে।
import type { PayStatus } from './model';

export interface LegacyMode {
  steps: () => string[];
  sendLabel: () => string;
  trxLabel: () => string;
  trxHint: () => string;
  trxPh: () => string;
  trxMax: number;
  trxClean: (v: string) => string;
  trxValid: (v: string) => boolean;
  trxErr: () => string;
  /** "পাঠিয়েছি" — খালি TrxID চলে */
  claim: (trx: string) => Promise<void>;
  errText: (e: unknown) => string;
  statusText: (st: PayStatus) => string;
  copiedToast: () => string;
  numberLabel: () => string;
  checkingText: () => string;
  rejectedText: () => string;
  reasonPrefix: () => string;
  howTo: () => string;
}
