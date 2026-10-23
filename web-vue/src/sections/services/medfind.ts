import { num, tr, trPlain } from '@/lib/i18n';

export interface Drug { i: number; name: string; generic: string; form: string; company: string }
/** a shop that has it: haveQty in haveUnit (the buyer's unit); full = at least what the buyer asked */
export interface FindShop { id: string; name: string; phone: string; address: string; openNow: boolean; open24: boolean; km: number | null; haveQty?: number | null; haveUnit?: QtyUnit | null; full?: boolean }
export interface MyFind { id: string; drug: Drug; area: string; open: boolean; notified: number; answered: number; have: FindShop[]; createdAt: number; qty?: number | null; unit?: QtyUnit | null }

export const drugTitle = (d: Drug) => d.name + (d.form ? ' · ' + d.form : '');
export const drugDetail = (d: Drug) => [d.generic, d.form, d.company].filter(Boolean).join(' · ');

// ---- "কতটা লাগবে" (backend/docs/MEDFIND.md — the server's MedQty and the app's MedQty use the same rules) ----
export type QtyUnit = 'strip' | 'pcs' | 'bottle' | 'vial' | 'unit';
/** what a buyer may ask for */
export const QTY_MAX = 999;
/** what a pharmacy may say it has */
export const HAVE_MAX = 9999;

/** the units that fit the drug's form; the first is the default */
export function unitsFor(form: string | null | undefined): QtyUnit[] {
  const f = (form ?? '').toLowerCase();
  if (f.includes('inject') || f.includes('infusion') || f.includes('vaccine')) return ['vial'];
  if (f.includes('tablet') || f.includes('capsule') || f.includes('cozycap')) return ['strip', 'pcs'];
  if (f.includes('nebuliser') || f.includes('respirator')) return ['unit'];
  if (['syrup', 'suspension', 'elixir', 'linctus', 'oral solution'].some((k) => f.includes(k))) return ['bottle'];
  return ['unit'];
}

/** the unit's name on the choice buttons */
export function unitName(u: QtyUnit): string {
  switch (u) {
    case 'strip': return tr('Strip', 'পাতা'); // bn-ok
    case 'pcs': return tr('Pieces', 'পিস'); // bn-ok
    case 'bottle': return tr('Bottle', 'বোতল'); // bn-ok
    case 'vial': return tr('Vial', 'টি/ভায়াল'); // bn-ok
    default: return tr('Pieces', 'টি'); // bn-ok
  }
}

/** "৩ পাতা", "১০ পিস", "২টি/ভায়াল", "১টি" — "3 strips", "1 bottle" */
export function qtyText(qty: number, unit: QtyUnit | string | null | undefined): string {
  const one = qty === 1;
  const n = num(qty);
  switch (unit) {
    case 'strip': return trPlain(`${qty} ${one ? 'strip' : 'strips'}`, `${n} পাতা`); // bn-ok
    case 'pcs': return trPlain(`${qty} ${one ? 'pc' : 'pcs'}`, `${n} পিস`); // bn-ok
    case 'bottle': return trPlain(`${qty} ${one ? 'bottle' : 'bottles'}`, `${n} বোতল`); // bn-ok
    case 'vial': return trPlain(`${qty} ${one ? 'vial' : 'vials'}`, `${n}টি/ভায়াল`); // bn-ok
    default: return trPlain(`${qty} ${one ? 'pc' : 'pcs'}`, `${n}টি`); // bn-ok
  }
}

/** the unit word after a typed number: "পাতা", "পিস", "বোতল", "টি/ভায়াল", "টি" — "strips", "pcs" … */
export function unitWord(unit: string | null | undefined): string {
  switch (unit) {
    case 'strip': return trPlain('strips', 'পাতা'); // bn-ok
    case 'pcs': return trPlain('pcs', 'পিস'); // bn-ok
    case 'bottle': return trPlain('bottles', 'বোতল'); // bn-ok
    case 'vial': return trPlain('vials', 'টি/ভায়াল'); // bn-ok
    default: return trPlain('pcs', 'টি'); // bn-ok
  }
}

/** "৩ পাতা লাগবে" (empty for a find stored before amounts) */
export const needText = (qty: number | null | undefined, unit: string | null | undefined) =>
  qty ? trPlain(`Needs ${qtyText(qty, unit)}`, `${qtyText(qty, unit)} লাগবে`) : ''; // bn-ok

/** a typed amount → 1…max (anything else → null); Bangla digits allowed */
export function parseQty(v: string | number, max = QTY_MAX): number | null {
  const s = String(v).trim().replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d)));
  if (!/^\d{1,4}$/.test(s)) return null;
  const n = Number(s);
  return n >= 1 && n <= max ? n : null;
}

// The buyer's view of a shop that has it: "আছে — ২০ পিস · পুরোটা আছে" (at least what was asked), "আছে — ১০ পিস
// (আপনার দরকার ২০)" (less). Only shops that have it are listed — a shop that does not just never answers.
export function replyText(f: Pick<MyFind, 'qty' | 'unit'>, s: FindShop): string {
  if (!s.haveQty) return tr('Has it', 'আছে বলেছে');
  const have = qtyText(s.haveQty, s.haveUnit ?? f.unit);
  if (f.qty && s.haveQty >= f.qty) return trPlain(`Has ${have} · all you need`, `আছে — ${have} · পুরোটা আছে`); // bn-ok
  if (f.qty) return trPlain(`Has ${have} (you need ${f.qty})`, `আছে — ${have} (আপনার দরকার ${num(f.qty)})`); // bn-ok
  return trPlain(`Has ${have}`, `আছে — ${have}`); // bn-ok
}
