// offline_bootstrap / offline_sync-এর delta পড়া — সার্ভারের নাম একটু আলাদা হলেও চলে (camelCase বা ছোট নাম)।
// পড়া হয়: biz{name,phone,address}, demo, asOf, lastNo/lastSeq,
//   pharmacy: items[{id,name,code|barcode,generic,strength,form,unit,price|salePrice|mrp,cost,stock|qty,expiry}], removedItems[id]
//   lab: prices|labPrices|tests[{id,name,price,code,category}], fees[{label,amount}]
//   chamber: appointments[{id,date,chamberKey,serial,name,time,status,visitType}], fees{new,followup,report} | feeByVisit
import type { ChamberAppt, InvoiceFee, LabPrice, OfflineItem, VisitType } from '../types';

type O = Record<string, any>;
const str = (v: unknown) => (v == null ? undefined : String(v));
const numOr = (v: unknown, d = 0) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : d;
};

export function parseTime(v: unknown): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v < 1e12 ? v * 1000 : v;
  if (typeof v === 'string' && v) {
    const n = Number(v);
    if (Number.isFinite(n)) return parseTime(n);
    const t = Date.parse(v);
    if (Number.isFinite(t)) return t;
  }
  return 0;
}

export function parseItem(x: O): OfflineItem | null {
  const id = str(x?.id ?? x?.itemId);
  const name = str(x?.name)?.trim();
  if (!id || !name) return null;
  const it: OfflineItem = { id, name, price: numOr(x.price ?? x.salePrice ?? x.mrp), stock: numOr(x.stock ?? x.qty) };
  const code = str(x.code ?? x.barcode);
  if (code) it.code = code;
  for (const k of ['generic', 'strength', 'form', 'unit', 'expiry'] as const) {
    const v = str(x[k]);
    if (v) it[k] = v;
  }
  if (x.cost ?? x.costPrice) it.cost = numOr(x.cost ?? x.costPrice);
  return it;
}

export function parseLabPrice(x: O): LabPrice | null {
  const id = str(x?.id ?? x?.testId ?? x?.code ?? x?.name);
  const name = str(x?.name)?.trim();
  if (!id || !name) return null;
  const p: LabPrice = { id, name, price: numOr(x.price) };
  if (x.code) p.code = String(x.code);
  if (x.category) p.category = String(x.category);
  return p;
}

export function parseFees(v: unknown): InvoiceFee[] {
  return Array.isArray(v)
    ? v.filter((f) => f && f.label).map((f) => ({ label: String(f.label), amount: numOr(f.amount) }))
    : [];
}

const VISIT: VisitType[] = ['new', 'followup', 'report'];

/** চেম্বারের রোগী — শুধু যা দরকার (নাম, সিরিয়াল); ফোন রাখা হয় না (I4.5 / I6) */
export function parseAppt(x: O): ChamberAppt | null {
  const id = str(x?.id ?? x?.appointmentId);
  const date = str(x?.date ?? x?.day);
  if (!id || !date) return null;
  const a: ChamberAppt = {
    id,
    date: date.slice(0, 10),
    chamberKey: str(x.chamberKey ?? x.chamber) ?? '',
    name: str(x.name ?? x.patientName) ?? '',
    status: str(x.status) ?? 'booked',
  };
  if (x.serial != null) a.serial = numOr(x.serial);
  if (x.time) a.time = String(x.time);
  if (VISIT.includes(x.visitType)) a.visitType = x.visitType;
  if (x.feeNo) a.feeNo = String(x.feeNo);
  return a;
}

export function parseVisitFees(v: unknown): Partial<Record<VisitType, number>> {
  const out: Partial<Record<VisitType, number>> = {};
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const k of VISIT) if ((v as O)[k] != null) out[k] = numOr((v as O)[k]);
  }
  return out;
}

export interface Snapshot {
  biz: { name: string; phone?: string; address?: string } | null;
  demo: boolean;
  asOf: number;
  seqFloor: number;
  items: OfflineItem[] | null;
  removedItems: string[];
  labPrices: LabPrice[] | null;
  labFees: InvoiceFee[] | null;
  appts: ChamberAppt[] | null;
  removedAppts: string[];
  visitFees: Partial<Record<VisitType, number>> | null;
}

const list = (v: unknown) => (Array.isArray(v) ? v : null);

/** bootstrap বা delta → একই রূপ; null = এই অংশ আসেনি (আগেরটা থাকে) */
export function parseSnapshot(raw: unknown, series = ''): Snapshot {
  const s: O = raw && typeof raw === 'object' ? (raw as O) : {};
  const b: O | null = s.biz ?? s.business ?? null;
  const items = list(s.items ?? s.pharmacy?.items);
  const prices = list(s.prices ?? s.labPrices ?? s.tests ?? s.lab?.prices);
  const appts = list(s.appointments ?? s.appts ?? s.chamber?.appointments);
  const lastNo = str(s.lastNo);
  let seqFloor = numOr(s.lastSeq ?? s.seq, 0);
  if (lastNo && series && lastNo.startsWith(series + '-')) seqFloor = Math.max(seqFloor, numOr(lastNo.slice(series.length + 1).split('-')[0]));
  const vf = s.feeByVisit ?? s.visitFees ?? (s.fees && !Array.isArray(s.fees) ? s.fees : null);
  return {
    biz: b && b.name ? { name: String(b.name), phone: str(b.phone), address: str(b.address) } : null,
    demo: !!(s.demo ?? b?.demo),
    asOf: parseTime(s.asOf),
    seqFloor,
    items: items ? (items.map(parseItem).filter(Boolean) as OfflineItem[]) : null,
    removedItems: (list(s.removedItems ?? s.removed) ?? []).map(String),
    labPrices: prices ? (prices.map(parseLabPrice).filter(Boolean) as LabPrice[]) : null,
    labFees: Array.isArray(s.fees) || Array.isArray(s.labFees) ? parseFees(s.labFees ?? s.fees) : null,
    appts: appts ? (appts.map(parseAppt).filter(Boolean) as ChamberAppt[]) : null,
    removedAppts: (list(s.removedAppointments) ?? []).map(String),
    visitFees: vf ? parseVisitFees(vf) : null,
  };
}
