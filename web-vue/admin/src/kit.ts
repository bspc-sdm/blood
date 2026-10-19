// প্যানেলের ছোট অংশ: কাজের বোতাম, টেবিল, টুলবার, কারণের ঘর, পাতার খোলস
import { defineComponent, nextTick, onUnmounted, ref, shallowRef, type PropType, type VNodeChild } from 'vue';
import { c, h, icon, type Kid } from './h';
import { S, act, afterChange, loadOverview, route, type Route } from './state';
import { FIELD_LABEL, L, REASONS, WEEKDAYS, ago, bn, errMsg, fmtDT, fmtDate, num, showVal, toast, type J } from './util';

// =========================================================
// কাজের বাটন: warn হলে প্রথম চাপে "নিশ্চিত?"
// =========================================================
export const ActBtn = defineComponent({
  name: 'ActBtn',
  props: {
    label: { type: String, required: true },
    kind: { type: String, required: true },
    run: { type: Function as PropType<() => unknown>, required: true },
    okMsg: { type: String as PropType<string | null>, default: null },
    after: { type: Function as PropType<() => unknown>, default: null },
    /** কাজ শেষে বোতাম আবার আগের মতো (যেমন "আরও আগের") */
    resetAfter: { type: Boolean, default: false },
  },
  setup(p) {
    const el = ref<HTMLButtonElement | null>(null);
    const armed = ref(false);
    const text = ref(p.label);
    let t: ReturnType<typeof setTimeout> | undefined;
    onUnmounted(() => clearTimeout(t));
    async function click() {
      const b = el.value;
      if (!b) return;
      if (p.kind === 'warn' && !armed.value) {
        armed.value = true;
        text.value = 'নিশ্চিত? আবার চাপুন';
        t = setTimeout(() => {
          armed.value = false;
          text.value = p.label;
        }, 4000);
        return;
      }
      clearTimeout(t);
      const scope = (b.closest('tr, .ctl, .card') as HTMLElement | null) || b.parentElement!;
      const buttons = scope.querySelectorAll('button');
      buttons.forEach((x) => {
        x.disabled = true;
      });
      text.value = '…';
      S.busy++;
      try {
        await p.run();
        if (p.okMsg) toast(p.okMsg);
        if (p.after) await p.after();
        if (p.resetAfter) {
          b.disabled = false;
          text.value = p.label;
        }
      } catch (e) {
        buttons.forEach((x) => {
          x.disabled = false;
        });
        armed.value = false;
        text.value = p.label;
        const old = scope.querySelector('.row-error');
        if (old) old.remove();
        const holder = b.closest('td') || scope;
        if (S.loggedIn) {
          const d = document.createElement('div');
          d.className = 'row-error';
          d.setAttribute('role', 'alert');
          d.textContent = errMsg(e);
          holder.append(d);
        }
      } finally {
        S.busy--;
      }
    }
    return () => h('button', { ref: el, type: 'button', class: `btn ${p.kind}${armed.value ? ' armed' : ''}`, onclick: click, text: text.value });
  },
});

export const actionBtn = (label: string, kind: string, run: () => unknown, okMsg?: string | null, after?: (() => unknown) | null, key?: string | number) =>
  c(ActBtn, { label, kind, run, okMsg: okMsg ?? null, after: after ?? null, ...(key != null ? { key } : {}) });

// =========================================================
// টেবিল
// =========================================================
export interface Col {
  label: string;
  main?: boolean;
  cell: (x: J) => Kid;
}
/** cols: [{label, cell(item) → node|string, main?}], actions(item) → [buttons] */
export function table(items: J[], cols: Col[], actions?: ((x: J) => Kid) | null, onRow?: ((x: J) => void) | null) {
  if (!items.length) return h('div', { class: 'card empty', text: 'এখানে কিছু নেই।' });
  return h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
    h('thead', null, h('tr', null, cols.map((col) => h('th', { scope: 'col', text: col.label })), actions ? h('th', null) : null)),
    h('tbody', null, items.map((it) => h('tr', {
      class: onRow ? 'click' : null,
      onclick: onRow ? (ev: MouseEvent) => {
        if (!(ev.target as Element).closest('button, a')) onRow(it);
      } : null,
    },
    cols.map((col, i) => h('td', { class: col.main ? 'main-cell' : null, 'data-label': i === 0 ? null : col.label }, col.cell(it))),
    actions ? h('td', { class: 'act' }, h('div', { class: 'actions' }, actions(it))) : null)))));
}

export function toolbar(o: { tabs?: [string, string][]; current?: string; base?: string; search?: string; q?: string; onSearch?: (v: string) => void; count?: number | null; extra?: Kid }) {
  return h('div', { class: 'toolbar' },
    o.tabs ? h('div', { class: 'seg', role: 'tablist' }, o.tabs.map(([key, label]) =>
      h('a', { href: `#/${o.base}?s=${key}`, class: key === o.current ? 'on' : null, role: 'tab', 'aria-selected': key === o.current ? 'true' : 'false', text: label }))) : null,
    o.onSearch ? h('label', { class: 'search' }, icon('search'),
      h('input', { type: 'search', placeholder: o.search || 'খুঁজুন', 'aria-label': o.search || 'খুঁজুন', value: o.q ?? '', oninput: (e: Event) => o.onSearch!((e.target as HTMLInputElement).value) })) : null,
    o.count != null ? h('span', { class: 'count', text: `${bn(o.count)}টা` }) : null,
    o.extra);
}

export const userLink = (uid: string | null | undefined, text?: string) => (uid ? h('a', { href: `#/user/${encodeURIComponent(uid)}`, text: text || 'ব্যবহারকারী দেখুন' }) : null);

export function kpi(label: string, value: unknown, sub?: string | null) {
  return h('div', { class: 'card kpi' }, h('div', { class: 'v', text: num(value) }), h('div', { class: 'k', text: label }), sub ? h('div', { class: 'd', text: sub }) : null);
}

export const listingChip = (st: string) => h('span', { class: `chip ${st === 'approved' ? 'good' : st === 'suspended' || st === 'rejected' ? 'bad' : 'pri'}`, text: L.listing[st] || st || '' });

// =========================================================
// কারণের ঘর — মালিক নোটিফিকেশনে আর নিজের কার্ডে দেখেন। তালিকা থেকে বাছা বা নিজে লেখা
// =========================================================
export interface Holder {
  value: string;
}
const holders = new WeakMap<object, Record<string, Holder>>();
/** একটা জিনিসের (সারির) জন্য লেখার ঘরের মান — আবার আঁকলেও লেখা থাকে */
export function hold(obj: object, slot = 'reason'): Holder {
  let m = holders.get(obj);
  if (!m) holders.set(obj, (m = {}));
  return (m[slot] ??= { value: '' });
}
function ensureReasonList() {
  if (document.getElementById('reason-list')) return;
  const dl = document.createElement('datalist');
  dl.id = 'reason-list';
  for (const r of REASONS) {
    const o = document.createElement('option');
    o.value = r;
    dl.append(o);
  }
  document.body.append(dl);
}
export function reasonBox(hd: Holder) {
  ensureReasonList();
  return h('input', { class: 'reason', list: 'reason-list', maxlength: 200, placeholder: 'কারণ (মালিক দেখবেন)', 'aria-label': 'কারণ', value: hd.value, oninput: (e: Event) => { hd.value = (e.target as HTMLInputElement).value; } });
}

export function statusActions(action: string, id: string, st: string, what: string, owner: object) {
  const reason = hold(owner, `status:${action}:${id}`);
  const run = (status: string) => () => act(action, { id, status, reason: reason.value.trim() });
  // অনুমোদন শুধু যাচাইয়ের পাতা থেকে (কাগজ দেখে সব টিক দিয়ে)
  const kind = ({ admin_pharmacy_resolve: 'pharmacies', admin_ambulance_resolve: 'ambulances', admin_bloodbank_resolve: 'bloodBanks', admin_lab_resolve: 'labs' } as Record<string, string>)[action];
  const verifyBtn = h('a', { class: 'btn primary', href: `#/verify/${kind}:${encodeURIComponent(id)}`, text: 'যাচাই করে অনুমোদন' });
  if (st === 'pending') return [reasonBox(reason), verifyBtn, actionBtn('বাতিল', 'warn', run('rejected'), `${what} বাতিল করা হয়েছে`, afterChange)];
  if (st === 'approved') return [reasonBox(reason), actionBtn('বন্ধ করুন', 'warn', run('suspended'), `${what} বন্ধ করা হয়েছে`, afterChange)];
  if (st === 'suspended') return [actionBtn('আবার চালু করুন', 'tonal', run('approved'), `${what} আবার চালু হয়েছে`, afterChange)];
  return [verifyBtn];
}

/** অনুমোদিত তথ্যের পর কী বদলেছে, কোন কারণে বাতিল/বন্ধ, আর একই ফোন/গাড়ি/লাইসেন্সের অন্য তালিকা */
export function reviewCell(x: J): Kid {
  const out: Kid[] = [];
  if (x.verify) {
    const req = x.verify.docs.filter((d: J) => d.required);
    const miss = req.filter((d: J) => !d.has);
    const got = x.verify.docs.filter((d: J) => d.has).length;
    out.push(h('div', { class: 'chips' },
      h('span', { class: `chip ${miss.length ? 'bad' : 'good'}`, text: miss.length ? `কাগজ বাকি: ${bn(miss.length)}টা` : `কাগজ: ${bn(got)}টা` }),
      x.verify.docsRequestedAt ? h('span', { class: 'chip', text: `ছবি চাওয়া ${fmtDate(x.verify.docsRequestedAt)}` }) : null));
  }
  if (x.verification) out.push(h('div', { class: 'small', text: `যাচাই: ${x.verification.byEmail || '—'} · ${fmtDate(x.verification.at)}` }));
  if (x.statusReason) out.push(h('div', { class: 'small', text: `কারণ: ${x.statusReason}` }));
  if (x.editRejectedReason) out.push(h('div', { class: 'small', text: `শেষ বদল বাতিলের কারণ: ${x.editRejectedReason}` }));
  (x.changes || []).filter((ch: J) => ch.field !== 'lng').forEach((ch: J) => out.push(h('div', { class: 'small' },
    h('b', { text: `${FIELD_LABEL[ch.field] || ch.field}: ` }), ch.field === 'lat' ? 'বদলেছে' : `আগে ${showVal(ch.field, ch.before)} → এখন ${showVal(ch.field, ch.after)}`)));
  (x.duplicates || []).forEach((d: J) => out.push(h('div', { class: 'small' },
    h('span', { class: 'chip bad', text: `একই ${FIELD_LABEL[d.field] || d.field}` }), ' ', `${d.name || '—'} (${L.listing[d.status] || d.status})`)));
  return out.length ? out : '—';
}
export const hasReview = (x: J) => reviewCell(x) !== '—';

export const hoursText = (x: J) => (x.open24 ? '২৪ ঘণ্টা' : x.openAt ? `${bn(x.openAt)}–${bn(x.closeAt)}` : '—');
export const closedDaysNote = (x: J) => ((x.closedDays || []).length ? h('div', { class: 'small', text: `বন্ধ: ${x.closedDays.map((d: number) => WEEKDAYS[d]).join(', ')}` }) : null);

// =========================================================
// কাজের ইতিহাস
// =========================================================
export function logTable(items: J[]) {
  return table(items, [
    { label: 'কখন', main: true, cell: (x) => [fmtDT(x.at), h('div', { class: 'small', text: ago(x.at) })] },
    { label: 'কে', cell: (x) => [x.by || '—', h('div', { class: 'small', text: x.via === 'web' ? 'ওয়েব থেকে' : 'অ্যাপ থেকে' })] },
    { label: 'কী', cell: (x) => [L.log[x.action] || x.action, ': ', L.detail[x.detail] || x.detail || ''] },
    { label: 'কোথায়', cell: (x) => {
      const m = /^(users|pharmacies|badges)\/([A-Za-z0-9_-]+)$/.exec(x.target || '');
      const t = /^tickets\/([A-Za-z0-9_-]+)$/.exec(x.target || '');
      if (m) return userLink(m[2], 'ব্যবহারকারী');
      if (t) return h('a', { href: `#/ticket/${encodeURIComponent(t[1])}`, text: 'আপিল' });
      return h('span', { class: 'small', text: x.target || '' });
    } },
  ]);
}

// =========================================================
// পাতার খোলস: আগে "লোড হচ্ছে…", মেনু থেকে এলে আগে সার্ভারের নতুন তথ্য, সমস্যা হলে "আবার চেষ্টা করুন"
// =========================================================
export interface PageCtx {
  /** পুরোনো রাউটের দেরিতে আসা উত্তর যাতে নতুন পাতা না ঢেকে দেয় */
  alive: () => boolean;
}
export type Painter = () => VNodeChild;
export function definePage(name: string, load: (r: Route, ctx: PageCtx) => Promise<Painter | void> | Painter | void) {
  return defineComponent({
    name,
    props: {
      r: { type: Object as PropType<Route>, required: true },
      fresh: { type: Boolean, default: false },
      keepY: { type: Number as PropType<number | null>, default: null },
    },
    setup(props) {
      const view = shallowRef<Painter | null>(null);
      const err = ref('');
      let alive = true;
      onUnmounted(() => {
        alive = false;
      });
      const run = async () => {
        if (props.fresh) {
          S.lists = {};
          S.stats = null;
          await loadOverview();
          if (!alive) return;
        }
        const v = await load(props.r, { alive: () => alive });
        if (alive && v) view.value = v;
      };
      run()
        .catch((e) => {
          if (alive) err.value = errMsg(e);
        })
        .then(() => {
          if (alive && props.keepY != null) nextTick(() => window.scrollTo(0, props.keepY!));
        });
      return () => (err.value
        ? [h('p', { class: 'error', role: 'alert', text: err.value }), h('button', { class: 'btn quiet', type: 'button', onclick: () => route(false, true), text: 'আবার চেষ্টা করুন' })]
        : view.value
          ? view.value()
          : h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    },
  });
}
