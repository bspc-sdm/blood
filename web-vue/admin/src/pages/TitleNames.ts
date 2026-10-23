// "‘ডা.’ লেখা নাম" (backend/docs/PRACTICE.md §৬, সুইচ title_guard): MBBS/BDS (এলোপ্যাথি ডাক্তার) নন, অথচ নামের আগে
// "ডা."/"Dr." — তালিকা, ব্যাজ, ব্যাজ সংশোধনের আবেদন বা প্রোফাইলে। সুইচ চালু থাকুক বা না থাকুক দেখায়, যাতে মালিক আগে থেকে
// তাঁদের নাম ঠিক করতে বলতে পারেন। নাম যেমন জমা আছে তেমন (সুইচ চালু হলে অ্যাপ/ওয়েবে পদবি ছাড়া দেখায়, জমা নাম বদলায় না)।
import { h } from '../h';
import { definePage, table, userLink } from '../kit';
import { act, setTitle } from '../state';
import { bn, type J } from '../util';

const WHERE: Record<string, string> = {
  listing: 'তালিকা', // bn-ok
  badge: 'ব্যাজ', // bn-ok
  correction: 'ব্যাজ সংশোধনের আবেদন', // bn-ok
  profile: 'প্রোফাইল', // bn-ok
};

export default definePage('PageTitleNames', async (_r, { alive }) => {
  const res = await act('admin_title_names');
  if (!alive()) return;
  const items: J[] = res.items || [];
  setTitle(res.title || '‘ডা.’ লেখা নাম', `${bn(res.real ?? 0)} জন (ডেমো বাদে) · মোট ${bn(res.count ?? items.length)}`); // bn-ok
  return () => [
    h('p', { class: 'note', 'data-testid': 'title-note' }, [
      'এঁরা MBBS/BDS (এলোপ্যাথি ডাক্তার) নন, কিন্তু নামের আগে "ডা."/"Dr." লেখা। তাঁদের নাম ঠিক করতে বলুন: প্রোফাইল এডিটে নাম বদলে ব্যাজের তথ্য সংশোধন পাঠাবেন। ', // bn-ok
      '"‘ডা.’ পদবির নিয়ম" সুইচ চালু থাকলে অ্যাপ আর ওয়েবে পদবি ছাড়া দেখায় — জমা নাম বদলায় না।', // bn-ok
    ]),
    table(items, [
      { label: 'নাম', main: true, cell: (x) => [x.name || '—', h('div', { class: 'small', text: `দেখাবে: ${x.stripped || '—'}` }), h('div', { class: 'small' }, userLink(x.uid))] }, // bn-ok
      { label: 'পেশা', cell: (x) => [x.typeLabel || x.type || '—', x.demo ? h('div', { class: 'chips' }, h('span', { class: 'chip', text: 'ডেমো' })) : null] }, // bn-ok
      { label: 'কোথায়', cell: (x) => h('div', { class: 'chips' }, (x.where || []).map((w: string) => h('span', { class: 'chip bad', text: WHERE[w] || w }))) }, // bn-ok
      { label: 'যোগাযোগ', cell: (x) => [x.email || '—', x.phone ? h('div', { class: 'small', text: bn(x.phone) }) : null] }, // bn-ok
    ]),
  ];
});
