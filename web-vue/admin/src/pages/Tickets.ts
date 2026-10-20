// আপিল ও সমস্যা: তালিকা আর একটা আপিলের কথোপকথন
import { ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, table, toolbar, userLink } from '../kit';
import { S, act, afterChange, getList, go, setTitle } from '../state';
import { L, ago, bn, fmtDT, matches, type J } from '../util';

export const Tickets = definePage('PageTickets', async (r) => {
  const tabs: [string, string][] = [['open', 'উত্তরের অপেক্ষায়'], ['answered', 'উত্তর দেওয়া'], ['closed', 'বন্ধ']];
  const asked = r.params.get('s');
  const st = tabs.some(([k]) => k === asked) ? (asked as string) : 'open';
  setTitle('আপিল ও সমস্যা', L.ticket[st]);
  const items = await getList('tickets', st);
  const q = ref('');
  return () => {
    const shown = items.filter((x) => matches(q.value, x.name, x.email, L.category[x.category], ...(x.messages || []).map((m: J) => m.text)));
    return [
      toolbar({ tabs, current: st, base: 'tickets', search: 'নাম, ইমেইল বা লেখা', q: q.value, onSearch: (v) => { q.value = v; }, extra: h('span', { class: 'count', text: `${bn(shown.length)}টা` }) }),
      h('p', { class: 'note', text: 'অ্যাপের "সাহায্য ও আপিল" থেকে আসে। খোলা আপিলে যিনি বেশিক্ষণ অপেক্ষা করছেন, তিনি আগে।' }),
      h('div', null, table(shown, [
        { label: 'কে', main: true, cell: (x) => [x.name || '—', h('div', { class: 'small', text: x.email || '' })] },
        { label: 'বিষয়', cell: (x) => h('span', { class: 'chip pri', text: L.category[x.category] || x.category }) },
        { label: 'শেষ বার্তা', cell: (x) => {
          const m = (x.messages || [])[x.messages.length - 1];
          return m ? [h('div', { class: 'body', text: m.text.length > 140 ? `${m.text.slice(0, 140)}…` : m.text }), h('div', { class: 'small', text: `${m.from === 'admin' ? 'আমরা' : 'ব্যবহারকারী'}, ${ago(m.at)}` })] : '—';
        } },
        { label: 'বার্তা', cell: (x) => bn((x.messages || []).length) },
      ], null, (x) => go(`#/ticket/${encodeURIComponent(x.id)}`))),
    ];
  };
});

export const Ticket = definePage('PageTicket', async (r, { alive }) => {
  setTitle('আপিল', '');
  const res = await act('admin_ticket', { id: r.arg });
  if (!alive()) return;
  const t = res.item;
  setTitle(`আপিল: ${L.category[t.category] || t.category}`, L.ticket[t.status]);
  const reply = ref('');
  const send = (close: boolean) => async () => {
    const text = reply.value.trim();
    if (!text && !close) throw new Error('উত্তর লিখুন');
    await act('admin_ticket_reply', { id: t.id, text, close });
  };
  const doneTo = async () => {
    delete S.lists['tickets:answered'];
    delete S.lists['tickets:closed'];
    await afterChange();
  };
  return () => [
    h('div', { class: 'toolbar' }, h('a', { href: '#/tickets', text: 'সব আপিল' })),
    h('div', { class: 'card pad' },
      h('div', { class: 'profile' },
        h('div', null, h('h2', { text: t.name || '—' }), h('div', { class: 'note', text: t.email || '' }), h('div', { class: 'small' }, userLink(t.uid, 'এই ব্যবহারকারীর সব কিছু দেখুন ও ব্যবস্থা নিন'))),
        h('div', null, h('span', { class: `chip ${t.status === 'closed' ? '' : t.status === 'open' ? 'bad' : 'good'}`, text: L.ticket[t.status] }), h('div', { class: 'small', text: `খোলা হয়েছে ${fmtDT(t.createdAt)}` }))),
      h('div', { class: 'convo' }, (t.messages || []).map((m: J) => h('div', { class: `msg ${m.from === 'admin' ? 'admin' : 'user'}` },
        m.text, h('span', { class: 'when', text: `${m.from === 'admin' ? 'Lifolo টিম' : t.name || 'ব্যবহারকারী'}, ${fmtDT(m.at)}` })))),
      h('textarea', { class: 'reply', placeholder: 'উত্তর লিখুন। ব্যবহারকারী অ্যাপে নোটিফিকেশন পাবেন।', 'aria-label': 'উত্তর', maxlength: 1500, value: reply.value, oninput: (e: Event) => { reply.value = (e.target as HTMLTextAreaElement).value; } }),
      h('div', { class: 'reply-actions' },
        actionBtn('উত্তর পাঠান', 'primary', send(false), 'উত্তর পাঠানো হয়েছে', doneTo),
        actionBtn('উত্তর দিয়ে বন্ধ করুন', 'tonal', send(true), 'উত্তর পাঠিয়ে বন্ধ করা হয়েছে', doneTo),
        t.status !== 'closed' ? actionBtn('উত্তর ছাড়া বন্ধ করুন', 'warn', () => act('admin_ticket_reply', { id: t.id, close: true }), 'বন্ধ করা হয়েছে', doneTo) : null)),
  ];
});
