// অফলাইন (ডেস্কটপ) — পর্দা আর ইঞ্জিনের মাঝের চুক্তি। CONTRACT / backend/docs/INVOICES.md-এর "Offline (desktop) sync"
// আর "Invoice object"-এর নাম হুবহু। এই ফাইল ডেস্কটপ এজেন্টের; নাম বদলাতে হলে INVOICES.md-তে আগে।

/** রসিদ কে দেয় */
export type InvoiceKind = 'pharmacy' | 'lab' | 'chamber' | 'ambulance';
/** ডেস্কটপে অফলাইনে যে ব্যবসা চলে (অ্যাম্বুলেন্স ফোনে) */
export type BizKind = 'pharmacy' | 'lab' | 'chamber';
export type PayMethod = 'cash' | 'bkash' | 'nagad' | 'card' | 'other';
export type Paper = '58' | '80' | 'A4';

export interface InvoiceLine {
  name: string;
  qty: number;
  unit?: string;
  price: number;
  disc: number;
  amount: number;
  code?: string;
}
export interface InvoiceFee {
  label: string;
  amount: number;
}
export interface InvoicePayment {
  at: number;
  amount: number;
  method: PayMethod;
  ref?: string;
}

/** সার্ভারের রসিদ (Invoice object)। অফলাইনে বানানো রসিদে `id` সিঙ্ক হওয়ার আগে '' আর `offline: true`। */
export interface Invoice {
  id: string;
  no: string;
  kind: InvoiceKind;
  bizId: string;
  bizName: string;
  bizPhone?: string;
  bizAddress?: string;
  patientUid?: string;
  customer: { name?: string; phone?: string };
  refType?: 'book_sale' | 'rx_order' | 'lab_order' | 'appointment' | 'ambreq';
  refId?: string;
  lines: InvoiceLine[];
  subtotal: number;
  disc: number;
  fees: InvoiceFee[];
  total: number;
  paid: number;
  due: number;
  payments: InvoicePayment[];
  status: 'issued' | 'void';
  voidReason?: string;
  issuedAt: number;
  issuedBy: string;
  deviceId?: string;
  offline: boolean;
  verifyCode: string;
  verifyUrl: string;
  /** শুধু এই কম্পিউটারে (সার্ভারের JSON-এ নেই): অফলাইনে বানানোর আইডি আর সিঙ্কের অবস্থা */
  localId?: string;
  sync?: SyncState;
  /** সার্ভার রসিদটা নেয়নি (যেমন ৩০ দিনের বেশি পুরোনো) — কারণ */
  syncError?: string;
  /** সার্ভারের সতর্কতা, যেমন "stock_short" */
  warn?: string;
}

export type SyncState = 'pending' | 'synced' | 'rejected';

// ---------------------------------------------------------------- ক্যাশ থেকে পড়া

/** ফার্মেসির জিনিস (offline_bootstrap-এর items) — stock = সার্ভারের stock + এখনো সিঙ্ক না হওয়া বদল */
export interface OfflineItem {
  id: string;
  name: string;
  code?: string;
  generic?: string;
  strength?: string;
  form?: string;
  unit?: string;
  price: number;
  cost?: number;
  stock: number;
  expiry?: string;
}

export interface LabPrice {
  id: string;
  name: string;
  price: number;
  code?: string;
  category?: string;
}
export interface LabPrices {
  items: LabPrice[];
  fees: InvoiceFee[];
}

export type VisitType = 'new' | 'followup' | 'report';
export interface ChamberAppt {
  id: string;
  date: string;
  chamberKey: string;
  serial?: number;
  name: string;
  phone?: string;
  time?: string;
  status: string;
  visitType?: VisitType;
  /** ফি নেওয়া হয়েছে (রসিদ নম্বর) */
  feeNo?: string;
  /** অফলাইনে আসা নতুন রোগী, এখনো সিঙ্ক হয়নি */
  local?: boolean;
}
export interface ChamberDay {
  date: string;
  appointments: ChamberAppt[];
  fees: Partial<Record<VisitType, number>>;
}

export interface ReadyInfo {
  deviceId: string;
  /** "D1", "D2" … */
  series: string;
  /** শেষ কবে সার্ভার থেকে ক্যাশ নেওয়া (epoch ms) */
  asOf: number;
  pending: number;
}

// ---------------------------------------------------------------- কাজ (op) — offline_sync-এ হুবহু `data` হিসেবে যায়
// ইঞ্জিন নিজে যোগ করে: data.localId, আর রসিদ হলে data.no, data.verifyCode, data.issuedAt (সার্ভার এগুলো রাখে)।

export interface CustomerIn {
  name?: string;
  phone?: string;
}
export interface PaymentIn {
  amount: number;
  method: PayMethod;
  ref?: string;
}
/** আগের রসিদ: সিঙ্ক হয়ে থাকলে id, নইলে localId (ইঞ্জিন সাথে opId আর no পাঠায়) */
export interface InvoiceRef {
  id?: string;
  localId?: string;
}

export interface SaleData {
  lines: { itemId?: string; name: string; qty: number; unit?: string; price: number; disc?: number; code?: string }[];
  customer?: CustomerIn;
  disc?: number;
  fees?: InvoiceFee[];
  /** না দিলে পুরো টাকা নগদে (books_sale_add-এর মতো) */
  payments?: PaymentIn[];
  rxOrderId?: string;
}
export interface PurchaseData {
  lines: { itemId?: string; name: string; qty: number; cost: number; price?: number; expiry?: string; batch?: string }[];
  supplier?: string;
  note?: string;
}
export interface AdjustData {
  itemId: string;
  /** + বা − (ভাঙা, মেয়াদ শেষ, গোনায় ভুল) */
  delta: number;
  reason?: string;
}
export interface LabBillData {
  labOrderId?: string;
  lines: { testId?: string; name: string; price: number; qty?: number; disc?: number; code?: string }[];
  customer?: CustomerIn;
  fees?: InvoiceFee[];
  disc?: number;
  payments?: PaymentIn[];
}
export interface FeeData {
  appointmentId?: string;
  walkIn?: { name: string; phone?: string; chamberKey: string; date: string };
  visitType: VisitType;
  amount: number;
  method: PayMethod;
  ref?: string;
}
export interface PayData {
  invoice: InvoiceRef;
  amount: number;
  method: PayMethod;
  ref?: string;
}
export interface VoidData {
  invoice: InvoiceRef;
  reason: string;
}
export interface CheckinData {
  appointmentId: string;
}
export interface WalkinData {
  name: string;
  phone?: string;
  chamberKey: string;
  date: string;
  visitType?: VisitType;
}

export type OfflineOp =
  | { type: 'sale'; data: SaleData }
  | { type: 'purchase'; data: PurchaseData }
  | { type: 'adjust'; data: AdjustData }
  | { type: 'lab_bill'; data: LabBillData }
  | { type: 'fee'; data: FeeData }
  | { type: 'pay'; data: PayData }
  | { type: 'void'; data: VoidData }
  | { type: 'checkin'; data: CheckinData }
  | { type: 'walkin'; data: WalkinData };
export type OpType = OfflineOp['type'];

export interface RecordResult {
  localId: string;
  /** রসিদের নম্বর (D1-000123); রসিদ না হলে (purchase/adjust/checkin/walkin) '' */
  no: string;
  /** ছাপার মতো রসিদ (sale/lab_bill/fee নতুন; pay/void বদলানো); রসিদ না হলে null */
  invoice: Invoice | null;
}

export interface PrintOptions {
  paper: Paper;
  /** ডিফল্ট বাংলা অঙ্ক; true হলে ইংরেজি অঙ্ক */
  englishDigits?: boolean;
  /** থার্মাল প্রিন্টারের নাম (Windows); না দিলে সেটিংসে বাছাই করাটা */
  printer?: string;
}

/** সিঙ্কের অবস্থা — onChange-এ */
export interface OfflineStatus {
  online: boolean;
  syncing: boolean;
  pending: number;
  /** সার্ভার যেসব নেয়নি (রসিদ বাতিল নয় — শুধু সিঙ্ক হয়নি; দোকানকে দেখাতে) */
  rejected: number;
  lastSyncAt: number;
  lastError?: string;
  /** এই কম্পিউটার বাতিল করা হয়েছে (403 device_revoked) — আর সিঙ্ক হবে না */
  revoked: boolean;
}

/** পর্দা যা ডাকে (CONTRACT-এর OfflineStore) */
export interface OfflineStore {
  isDesktop(): boolean;
  ready(kind: BizKind, bizId: string): Promise<ReadyInfo>;
  items(q?: string): Promise<OfflineItem[]>;
  labPrices(): Promise<LabPrices>;
  chamberDay(date: string): Promise<ChamberDay>;
  record(op: OfflineOp): Promise<RecordResult>;
  pending(): number;
  syncNow(): Promise<void>;
  onChange(cb: (s: OfflineStatus) => void): () => void;
  print(invoice: Invoice, opts: PrintOptions): Promise<void>;
}

/** অফলাইনে চলে না/প্রস্তুত নয় — message বাংলা/ইংরেজি (tr) */
export class OfflineError extends Error {
  constructor(
    message: string,
    public code:
      | 'not_desktop'
      | 'not_ready'
      | 'need_online'
      | 'login_expired'
      | 'device_revoked'
      | 'locked'
      | 'invalid'
      | 'print_failed',
  ) {
    super(message);
  }
}
