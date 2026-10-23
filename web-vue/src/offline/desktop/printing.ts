// ডেস্কটপের প্রিন্ট: ৫৮/৮০ মিমি → থার্মাল (ছবি → ESC/POS → Windows প্রিন্টারে RAW); A4 → WebView-এর প্রিন্ট জানালা।
// প্রিন্টারের পছন্দ এই কম্পিউটারের (localStorage — হারালে আবার বাছাই করলেই হয়)।
import { reactive } from 'vue';
import { escpos, PAPER_DOTS, toMono, trimBottom } from '../print/escpos';
import { printHtml } from '../print/html';
import { renderReceipt } from '../print/raster';
import { receiptModel } from '../print/receipt';
import { R, T } from '../text';
import { OfflineError, type Invoice, type PrintOptions } from '../types';
import { printRaw } from './native';

export interface PrintPrefs {
  printer: string;
  paper: '58' | '80' | 'A4';
  drawer: boolean;
  englishDigits: boolean;
}

const KEY = 'lf.desk.print';

function load(): PrintPrefs {
  const d: PrintPrefs = { printer: '', paper: '80', drawer: false, englishDigits: false };
  try {
    return { ...d, ...(JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<PrintPrefs>) };
  } catch {
    return d;
  }
}

export const printPrefs = reactive<PrintPrefs>(load());

export function savePrintPrefs(p: Partial<PrintPrefs>) {
  Object.assign(printPrefs, p);
  try {
    localStorage.setItem(KEY, JSON.stringify(printPrefs));
  } catch {
    /* পরের বার আবার বাছাই */
  }
}

/** থার্মাল প্রিন্টারের বাইট (পরীক্ষাতেও) */
export async function thermalBytes(inv: Invoice, paper: '58' | '80', englishDigits: boolean, drawer: boolean): Promise<Uint8Array> {
  const img = await renderReceipt(receiptModel(inv, englishDigits), PAPER_DOTS[paper]);
  return escpos(trimBottom(toMono(img.rgba, img.width, img.height)), { drawer, cut: true });
}

export async function printInvoice(inv: Invoice, opts: PrintOptions): Promise<void> {
  const paper = opts.paper ?? printPrefs.paper;
  const englishDigits = opts.englishDigits ?? printPrefs.englishDigits;
  if (paper === 'A4') return printHtml(inv, { paper, englishDigits });
  const printer = opts.printer || printPrefs.printer;
  if (!printer) throw new OfflineError(T.noPrinter(), 'print_failed');
  const cash = inv.id !== 'test' && inv.payments.some((p) => p.method === 'cash') && inv.status !== 'void';
  try {
    await printRaw(printer, await thermalBytes(inv, paper, englishDigits, printPrefs.drawer && cash));
  } catch (e) {
    throw new OfflineError(`${T.printFailed()}: ${String(e)}`, 'print_failed');
  }
}

/** সেটিংসের "টেস্ট প্রিন্ট" — বাংলা যুক্তাক্ষর, অঙ্ক, QR */
export function testInvoice(): Invoice {
  const now = Date.now();
  return {
    id: 'test',
    no: 'TEST-000001',
    kind: 'pharmacy',
    bizId: '',
    bizName: 'Lifolo',
    bizAddress: R.testLine,
    customer: { name: R.testTitle },
    lines: [
      { name: 'প্যারাসিটামল ৫০০ মিগ্রা (স্কয়ার)', qty: 2, price: 12.5, disc: 0, amount: 25 }, // bn-ok
      { name: 'ক্ষুদ্র যুক্তাক্ষর: ক্ষ ঙ্ক ন্ত্র স্ত্র', qty: 1, price: 0, disc: 0, amount: 0 }, // bn-ok
    ],
    subtotal: 25,
    disc: 0,
    fees: [],
    total: 25,
    paid: 25,
    due: 0,
    payments: [{ at: now, amount: 25, method: 'cash' }],
    status: 'issued',
    issuedAt: now,
    issuedBy: '',
    offline: false,
    verifyCode: 'TEST000000',
    verifyUrl: 'https://lifolo.com/v/TEST000000',
  };
}
