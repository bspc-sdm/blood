// জরুরি রক্তের আহ্বান (দুর্ঘটনা ইত্যাদি)
import { defineComponent, reactive, ref } from 'vue';
import { c, h, type Kid } from '../h';
import { actionBtn, definePage } from '../kit';
import { act, afterChange, setTitle } from '../state';
import { GROUPS, bn, fmtDT, toast, type J } from '../util';

const EmergencyForm = defineComponent({
  name: 'EmergencyForm',
  setup() {
    const districts = ref<string[]>([]);
    const district = ref('');
    const hospitals = ref<J[] | null>(null);
    const hospState = ref<'none' | 'loading' | 'ok' | 'fail'>('none');
    const hospital = ref('');
    const f = reactive({ hospitalText: '', loc: '', radius: '25', hours: '12', phone: '', note: '', confirm: false });
    const needs = reactive<Record<string, string>>(Object.fromEntries(GROUPS.map((g) => [g, ''])));
    act('admin_districts').then((r: J) => { districts.value = r.items || []; }).catch(() => {});
    async function pickDistrict(v: string) {
      district.value = v;
      hospital.value = '';
      hospState.value = 'loading';
      try {
        const r = await act('admin_hospitals', { district: v });
        hospitals.value = r.items || [];
        hospState.value = 'ok';
      } catch {
        hospState.value = 'fail';
      }
    }
    const val = (e: Event) => (e.target as HTMLInputElement).value;
    const field = (label: string, el: Kid) => h('label', { class: 'field' }, h('span', { text: label }), el);

    return () => h('div', { class: 'card pad bank-form' },
      h('div', { class: 'field-row' },
        field('জেলা', h('select', { 'aria-label': 'জেলা', value: district.value, onchange: (e: Event) => pickDistrict(val(e)) },
          h('option', { value: '', text: 'জেলা বেছে নিন' }), districts.value.map((d) => h('option', { value: d, text: d })))),
        field('হাসপাতাল', h('select', { 'aria-label': 'হাসপাতাল', disabled: hospState.value !== 'ok', value: hospital.value, onchange: (e: Event) => { hospital.value = val(e); } },
          hospState.value === 'none' ? h('option', { value: '', text: 'আগে জেলা বেছে নিন' })
            : hospState.value === 'loading' ? h('option', { value: '', text: 'লোড হচ্ছে…' })
              : hospState.value === 'fail' ? h('option', { value: '', text: 'আনা যায়নি' })
                : [h('option', { value: '', text: 'হাসপাতাল বেছে নিন' }), (hospitals.value || []).map((x) => h('option', { value: x.id, text: x.name }))]))),
      field('হাসপাতাল (তালিকায় না থাকলে)', h('input', { maxlength: 120, placeholder: 'তালিকায় না থাকলে নাম লিখুন', value: f.hospitalText, oninput: (e: Event) => { f.hospitalText = val(e); } })),
      field('হাসপাতালের লোকেশন (ঐচ্ছিক, দূরত্ব ঠিকভাবে মাপতে)', h('input', { placeholder: 'যেমন 24.7471, 90.4203 (Google Maps থেকে)', maxlength: 60, value: f.loc, oninput: (e: Event) => { f.loc = val(e); } })),
      h('div', { class: 'field' }, h('span', { text: 'কোন গ্রুপের কত ব্যাগ দরকার' }),
        h('div', { class: 'need-grid' }, GROUPS.map((g) => h('label', { class: 'need' }, h('b', { text: g }),
          h('input', { type: 'number', min: 0, max: 200, value: needs[g], placeholder: '০', 'aria-label': `${g} কত ব্যাগ`, oninput: (e: Event) => { needs[g] = val(e); } }))))),
      h('div', { class: 'field-row' },
        field('কত দূর পর্যন্ত জানানো হবে', h('select', { value: f.radius, onchange: (e: Event) => { f.radius = val(e); } }, [10, 25, 50].map((k) => h('option', { value: String(k), text: `${bn(k)} কিমি` })))),
        field('কতক্ষণ চালু থাকবে', h('select', { value: f.hours, onchange: (e: Event) => { f.hours = val(e); } }, [6, 12, 24, 48].map((k) => h('option', { value: String(k), text: `${bn(k)} ঘণ্টা` }))))),
      field('হাসপাতালের যোগাযোগের মোবাইল নম্বর', h('input', { inputmode: 'tel', maxlength: 15, placeholder: '01XXXXXXXXX', value: f.phone, oninput: (e: Event) => { f.phone = val(e); } })),
      field('বার্তা (ঐচ্ছিক)', h('input', { maxlength: 300, placeholder: 'যেমন: বাস দুর্ঘটনা, জরুরি বিভাগের ব্লাড ব্যাংকে আসুন', value: f.note, oninput: (e: Event) => { f.note = val(e); } })),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: f.confirm, onchange: (e: Event) => { f.confirm = (e.target as HTMLInputElement).checked; } }),
        h('span', { text: 'হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছি যে এই রক্ত দরকার' })),
      h('div', { class: 'actions' }, actionBtn('আহ্বান পাঠান', 'warn', async () => {
        const groups: Record<string, number> = {};
        GROUPS.forEach((g) => {
          const n = parseInt(needs[g], 10);
          if (n > 0) groups[g] = n;
        });
        let lat: number | undefined;
        let lng: number | undefined;
        const m = f.loc.match(/(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/);
        if (m) {
          lat = parseFloat(m[1]);
          lng = parseFloat(m[2]);
        }
        const r = await act('admin_emergency_create', {
          district: district.value, hospitalId: hospital.value || null, hospitalName: f.hospitalText.trim(),
          lat, lng, groups, radiusKm: parseInt(f.radius, 10), hours: parseInt(f.hours, 10),
          contactPhone: f.phone.trim(), note: f.note.trim(), confirmed: f.confirm,
        });
        toast(`${bn(r.notified || 0)} জন ডোনারকে জানানো হয়েছে`);
      }, 'আহ্বান চালু হয়েছে', afterChange)));
  },
});

export default definePage('PageEmergency', async (_r, { alive }) => {
  setTitle('জরুরি আহ্বান', 'দুর্ঘটনায় একসাথে অনেক রক্ত');
  const res = await act('admin_emergencies');
  if (!alive()) return;
  const items: J[] = res.items || [];
  const live = items.filter((x) => x.live);
  const past = items.filter((x) => !x.live);

  const card = (x: J) => {
    const rows = Object.entries(x.groups || {}).map(([g, v]: [string, J]) => {
      const full = (v.coming || 0) >= v.need;
      const state = v.adminClosed ? 'বন্ধ করা' : full ? 'যথেষ্ট আসছেন' : 'চালু';
      return h('tr', null,
        h('td', { 'data-label': 'গ্রুপ' }, h('b', { text: g })),
        h('td', { 'data-label': 'দরকার', text: `${bn(v.need)} ব্যাগ` }),
        h('td', { 'data-label': 'আসছেন', text: bn(v.coming || 0) }),
        h('td', { 'data-label': 'জানানো', text: bn(v.notified || 0) }),
        h('td', { 'data-label': 'অবস্থা' }, h('span', { class: `chip ${v.adminClosed ? 'bad' : full ? 'good' : 'pri'}`, text: state })),
        h('td', null, x.live ? (v.adminClosed
          ? actionBtn('আবার খুলুন', 'tonal', () => act('admin_emergency_group', { id: x.id, group: g, closed: false }), `${g} আবার খোলা হয়েছে`, afterChange)
          : actionBtn('এই গ্রুপ বন্ধ', 'quiet', () => act('admin_emergency_group', { id: x.id, group: g, closed: true }), `${g} বন্ধ করা হয়েছে`, afterChange)) : null));
    });
    return h('div', { class: 'card pad emergency' },
      h('div', { class: 'em-head' },
        h('div', null, h('h3', { text: x.hospitalName }),
          h('div', { class: 'note', text: `${x.district} · ${bn(x.radiusKm)} কিমির মধ্যে${x.exactLocation ? ' (হাসপাতালের লোকেশন থেকে)' : ' (জেলার মাঝখান থেকে)'} · যোগাযোগ ${bn(x.contactPhone)}` }),
          h('div', { class: 'note', text: `শুরু ${fmtDT(x.createdAt)} · ${x.live ? `শেষ হবে ${fmtDT(x.endsAt)}` : 'শেষ'} · ${bn(x.waves)} ধাপে জানানো · ${x.createdBy}` }),
          x.note ? h('div', { class: 'small', text: x.note }) : null),
        h('span', { class: `chip ${x.live ? 'bad' : ''}`, text: x.live ? 'চালু' : 'শেষ' })),
      h('div', { class: 'table-wrap' }, h('table', { class: 't' },
        h('thead', null, h('tr', null, ['গ্রুপ', 'দরকার', 'আসছেন', 'জানানো', 'অবস্থা', ''].map((t) => h('th', { text: t })))),
        h('tbody', null, rows))),
      x.live ? h('div', { class: 'actions' },
        actionBtn('আরও ডোনার জানান', 'primary', async () => {
          const r2 = await act('admin_emergency_more', { id: x.id });
          if (!r2.notified) throw new Error('নতুন করে জানানোর মতো আর কেউ নেই (বা সব গ্রুপ ভরে গেছে)। দূরত্ব বাড়িয়ে নতুন আহ্বান দিতে পারেন।');
        }, 'আরও ডোনারকে জানানো হয়েছে', afterChange),
        actionBtn('আহ্বান শেষ করুন', 'warn', () => act('admin_emergency_close', { id: x.id }), 'আহ্বান শেষ, যাঁরা আসছিলেন তাঁদের জানানো হয়েছে', afterChange)) : null);
  };

  return () => [
    h('p', { class: 'note', text: 'দুর্ঘটনা বা বড় জরুরি অবস্থায় হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে আহ্বান দিন। প্রতিটা গ্রুপে যত ব্যাগ দরকার তার প্রায় ৩ গুণ কাছের যোগ্য ডোনারকে আগে জানানো হয়; যথেষ্ট মানুষ "আসছি" বললে সেই গ্রুপ নিজে থেকে বন্ধ হয়, যাতে হাসপাতালে ভিড় না হয়।' }),
    live.length ? [h('h2', { class: 'section-title', text: 'চালু আহ্বান' }), ...live.map(card)] : null,
    h('h2', { class: 'section-title', text: 'নতুন আহ্বান' }),
    c(EmergencyForm),
    past.length ? [h('h2', { class: 'section-title', text: 'আগের আহ্বান' }), ...past.map(card)] : null,
  ];
});
