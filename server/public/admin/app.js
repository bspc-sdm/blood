// Lifolo ওয়েব অ্যাডমিন প্যানেল।
// নিরাপত্তা: ব্যবহারকারীর লেখা সবসময় textContent দিয়ে বসানো হয় (কখনো innerHTML নয়),
// লগইন httpOnly কুকিতে — এই স্ক্রিপ্ট সেটা দেখতে পায় না।
(function () {
  'use strict';

  const IDLE_MS = 30 * 60 * 1000;
  const AUTO_REFRESH_MS = 5 * 60 * 1000;
  const app = document.getElementById('app');
  const toastEl = document.getElementById('toast');

  let data = null;
  let loadedAt = 0;
  let lastActive = Date.now();
  let busy = 0;
  let loggedIn = false;

  // ---------------- ছোট সাহায্যকারী ----------------
  const BN = '০১২৩৪৫৬৭৮৯';
  const bn = (v) => String(v ?? '').replace(/[0-9]/g, (d) => BN[d]);

  /** DOM বানানো: স্ট্রিং সবসময় লেখা হিসেবে বসে */
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : String(v));
      }
    }
    for (const kid of kids.flat()) {
      if (kid == null || kid === false || kid === '') continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }

  const dateFmt = new Intl.DateTimeFormat('bn-BD', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'long', year: 'numeric' });
  const fmtDate = (ms) => {
    const d = new Date(ms);
    return ms && !Number.isNaN(d.getTime()) ? dateFmt.format(d) : '—';
  };

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toast.t);
    toast.t = setTimeout(() => { toastEl.hidden = true; }, 3200);
  }

  function store(key, value) {
    try {
      if (value == null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    } catch (e) { /* ব্রাউজারে জায়গা না থাকলে কিছু যায় আসে না */ }
  }
  function load(key) {
    try { return localStorage.getItem(key) || ''; } catch (e) { return ''; }
  }

  // ---------------- সার্ভার ----------------
  class ApiError extends Error {
    constructor(message, status, code) { super(message); this.status = status; this.code = code; }
  }

  async function post(path, body) {
    let res;
    try {
      res = await fetch(path, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Lifolo-Admin': '1' },
        body: JSON.stringify({ lang: 'bn', ...body }),
      });
    } catch (e) {
      throw new ApiError('ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। সংযোগ দেখে আবার চেষ্টা করুন।', 0);
    }
    let json = {};
    try { json = await res.json(); } catch (e) { /* খালি উত্তর */ }
    if (!res.ok) {
      const err = new ApiError(json.error || 'সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন', res.status, json.code);
      if (loggedIn && (res.status === 401 || json.code === 'session_expired')) {
        loggedIn = false;
        showLogin(err.message);
      }
      throw err;
    }
    return json;
  }
  const act = (action, body) => post('/api/community', { action, ...body });

  // ---------------- লগইন ----------------
  function setHeader(email) {
    document.getElementById('who').hidden = !email;
    document.getElementById('who-email').textContent = email || '';
  }

  function showLogin(message) {
    loggedIn = false;
    data = null;
    setHeader(null);
    let stage = 'email';
    let email = load('lf_admin_email');
    let error = message || '';
    let sending = false;

    function draw() {
      const form = h('form', { class: 'login', novalidate: true, onsubmit: submit });
      form.append(
        h('h1', { text: 'অ্যাডমিন লগইন' }),
        h('p', { text: stage === 'email'
          ? 'অ্যাপে যে ইমেইল দিয়ে লগইন করেন, সেটা দিন। ইমেইলে ৬ সংখ্যার কোড যাবে।'
          : `${email} ঠিকানায় কোড পাঠানো হয়েছে। ইমেইল খুলে কোডটা লিখুন।` }),
        error ? h('p', { class: 'error', role: 'alert', text: error }) : null,
      );
      if (stage === 'email') {
        form.append(
          h('label', { class: 'field' },
            h('span', { text: 'ইমেইল' }),
            h('input', { type: 'email', name: 'email', autocomplete: 'email', required: true, value: email, autofocus: true })),
          h('div', { class: 'row' },
            h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'পাঠানো হচ্ছে…' : 'কোড পাঠান' })),
        );
      } else {
        form.append(
          h('label', { class: 'field' },
            h('span', { text: 'কোড' }),
            h('input', { class: 'code-input', name: 'code', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, required: true, autofocus: true })),
          h('div', { class: 'row' },
            h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'যাচাই হচ্ছে…' : 'লগইন করুন' }),
            h('button', { class: 'link', type: 'button', onclick: () => { stage = 'email'; error = ''; draw(); }, text: 'ইমেইল বদলান' })),
        );
      }
      form.append(h('p', { class: 'note', text: 'শুধু অ্যাডমিনদের জন্য। ৩০ মিনিট কিছু না করলে নিজে থেকে লগআউট হবে।' }));
      app.replaceChildren(form);
      const first = form.querySelector('input');
      if (first) first.focus();
    }

    async function submit(ev) {
      ev.preventDefault();
      if (sending) return;
      const input = ev.target.querySelector('input');
      error = '';
      if (stage === 'email') {
        email = input.value.trim().toLowerCase();
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { error = 'সঠিক ইমেইল দিন'; draw(); return; }
        sending = true; draw();
        try {
          await post('/api/auth/send-code', { email });
          store('lf_admin_email', email);
          stage = 'code';
        } catch (e) { error = e.message; }
        sending = false; draw();
      } else {
        const code = input.value.replace(/[০-৯]/g, (d) => String(BN.indexOf(d))).replace(/\D/g, '');
        if (code.length !== 6) { error = '৬ সংখ্যার কোড দিন'; draw(); return; }
        sending = true; draw();
        try {
          await post('/api/auth/verify-code', { email, code, web: true });
          sending = false;
          lastActive = Date.now();
          await refresh(true);
          return;
        } catch (e) { error = e.message; }
        sending = false; draw();
      }
    }
    draw();
  }

  async function logout(message) {
    try { await act('web_logout'); } catch (e) { /* মেয়াদ শেষ হলেও লগইন পাতা দেখানো হবে */ }
    showLogin(message);
  }
  document.getElementById('logout').addEventListener('click', () => logout('লগআউট হয়েছে।'));

  // ---------------- তথ্য আনা ----------------
  async function refresh(first) {
    if (first) app.replaceChildren(h('p', { class: 'boot', text: 'লোড হচ্ছে…' }));
    try {
      data = await act('admin_overview');
      loadedAt = Date.now();
      loggedIn = true;
      setHeader(data.me && data.me.email);
      render();
    } catch (e) {
      if (e.status === 401 || e.status === 403 || e.code === 'session_expired') {
        showLogin(first ? (e.status === 403 ? e.message : '') : e.message);
      } else if (first || !data) {
        app.replaceChildren(
          h('p', { class: 'error', role: 'alert', text: e.message }),
          h('button', { class: 'btn quiet', type: 'button', onclick: () => refresh(true), text: 'আবার চেষ্টা করুন' }),
        );
      } else {
        toast(e.message);
      }
    }
  }

  // ---------------- অংশগুলোর সংজ্ঞা ----------------
  const BADGE = { doctor: 'ডাক্তার', nurse: 'নার্স', nutritionist: 'পুষ্টিবিদ', trainer: 'হেলথ ট্রেইনার' };
  const HOSPITAL_TYPE = {
    govt_mch: 'সরকারি মেডিকেল কলেজ হাসপাতাল',
    govt_specialized: 'সরকারি বিশেষায়িত হাসপাতাল',
    govt_district: 'জেলা / সদর হাসপাতাল',
    govt_uhc: 'উপজেলা স্বাস্থ্য কমপ্লেক্স',
    govt_hospital: 'অন্যান্য সরকারি হাসপাতাল',
    private_hospital: 'বেসরকারি হাসপাতাল',
    clinic: 'ক্লিনিক',
    diagnostic: 'ডায়াগনস্টিক সেন্টার',
  };
  const AMB_TYPE = { general: 'সাধারণ', ac: 'এসি', icu: 'আইসিইউ', freezer: 'ফ্রিজার ভ্যান' };
  const REPORT_KIND = { question: 'প্রশ্ন', answer: 'উত্তর', tip: 'টিপস', ambulance: 'অ্যাম্বুলেন্স', pharmacy: 'ফার্মেসি' };

  const SECTIONS = [
    { key: 'badges', title: 'ব্যাজের আবেদন', short: 'ব্যাজ', row: badgeRow },
    { key: 'pharmacies', title: 'অনুমোদনের অপেক্ষায় ফার্মেসি', short: 'ফার্মেসি', row: pharmacyRow },
    { key: 'ambulances', title: 'অনুমোদনের অপেক্ষায় অ্যাম্বুলেন্স', short: 'অ্যাম্বুলেন্স', row: ambulanceRow },
    { key: 'hospitalRequests', title: 'হাসপাতাল যোগের অনুরোধ', short: 'হাসপাতাল', row: hospitalRow },
    { key: 'reports', title: 'রিপোর্ট', short: 'রিপোর্ট', row: reportRow },
    { key: 'corrections', title: 'রক্তদানের তারিখ সংশোধন', short: 'তারিখ সংশোধন', row: correctionRow },
  ];

  const itemsOf = (key) => (data && data[key] && Array.isArray(data[key].items) ? data[key].items : []);

  /** কাজ শেষে তালিকা থেকে সরানো */
  function dropItem(key, pred) {
    if (data && data[key]) data[key].items = data[key].items.filter((x) => !pred(x));
  }

  /**
   * একটা কাজের বাটন। warn হলে প্রথম চাপে "নিশ্চিত?" হয়, ৪ সেকেন্ডের মধ্যে আবার চাপলে কাজ হয়।
   * run সফল হলে done(): তালিকা থেকে সরিয়ে আবার আঁকা।
   */
  function actionBtn(label, kind, run, okMsg, done) {
    const b = h('button', { type: 'button', class: `btn ${kind}`, text: label });
    let armed = false;
    let t = null;
    b.addEventListener('click', async () => {
      if (kind === 'warn' && !armed) {
        armed = true;
        b.classList.add('armed');
        b.textContent = 'নিশ্চিত? আবার চাপুন';
        t = setTimeout(() => { armed = false; b.classList.remove('armed'); b.textContent = label; }, 4000);
        return;
      }
      clearTimeout(t);
      const row = b.closest('.item, .list');
      const buttons = row ? row.querySelectorAll('button') : [b];
      buttons.forEach((x) => { x.disabled = true; });
      b.textContent = '…';
      busy++;
      try {
        await run();
        toast(okMsg);
        done();
        render();
      } catch (e) {
        buttons.forEach((x) => { x.disabled = false; });
        armed = false;
        b.classList.remove('armed');
        b.textContent = label;
        const holder = row && row.classList.contains('item') ? row : null;
        if (holder) {
          const old = holder.querySelector('.item-error');
          if (old) old.remove();
          holder.append(h('p', { class: 'item-error', role: 'alert', text: e.message }));
        } else if (loggedIn) {
          toast(e.message);
        }
      } finally {
        busy--;
      }
    });
    return b;
  }

  const meta = (...parts) => h('div', { class: 'meta' }, parts.filter(Boolean).map((p) => h('span', null, p)));
  const item = (main, actions) => h('article', { class: 'item' }, h('div', null, main), h('div', { class: 'actions' }, actions));

  function badgeRow(b) {
    const verify = b.type === 'doctor'
      ? h('a', { href: 'https://www.bmdc.org.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BMDC ওয়েবসাইটে নম্বর মিলিয়ে দেখুন' })
      : b.type === 'nurse'
        ? h('a', { href: 'https://www.bnmc.gov.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BNMC ওয়েবসাইটে নম্বর মিলিয়ে দেখুন' })
        : h('span', { class: 'hint', text: 'অনুমোদনের আগে সনদ দেখে নিন।' });
    const done = () => dropItem('badges', (x) => x.uid === b.uid);
    return item(
      [
        h('div', { class: 'title' }, b.name || '—', h('span', { class: 'chip', text: BADGE[b.type] || b.type || '' })),
        meta(b.email, `রেজি. নম্বর: ${b.regNo || '—'}`, `কর্মস্থল: ${b.workplace || '—'}`, b.createdAt ? `আবেদন: ${fmtDate(b.createdAt)}` : null),
        h('div', null, verify),
      ],
      [
        actionBtn('অনুমোদন', 'primary', () => act('admin_badge_resolve', { uid: b.uid, approve: true }), 'ব্যাজ অনুমোদন করা হয়েছে', done),
        actionBtn('বাতিল', 'warn', () => act('admin_badge_resolve', { uid: b.uid, approve: false }), 'আবেদন বাতিল করা হয়েছে', done),
      ],
    );
  }

  function pharmacyRow(p) {
    const done = () => dropItem('pharmacies', (x) => x.id === p.id);
    return item(
      [
        h('div', { class: 'title' }, p.name || '—'),
        meta(`লাইসেন্স: ${p.licenseNo || '—'}`, bn(p.phone), p.areaLabel || p.district),
        p.address ? h('div', { class: 'meta' }, h('span', null, p.address)) : null,
        h('div', { class: 'hint', text: 'অনুমোদনের আগে ঔষধ প্রশাসন অধিদপ্তরে (DGDA) লাইসেন্স যাচাই করুন।' }),
      ],
      [
        actionBtn('অনুমোদন', 'primary', () => act('admin_pharmacy_resolve', { id: p.id, approve: true }), 'ফার্মেসি অনুমোদন করা হয়েছে', done),
        actionBtn('বাতিল', 'warn', () => act('admin_pharmacy_resolve', { id: p.id, approve: false }), 'ফার্মেসি বাতিল করা হয়েছে', done),
      ],
    );
  }

  function ambulanceRow(a) {
    const done = () => dropItem('ambulances', (x) => x.id === a.id);
    return item(
      [
        h('div', { class: 'title' }, a.name || '—', (a.types || []).map((t) => h('span', { class: 'chip', text: AMB_TYPE[t] || t }))),
        meta(`গাড়ির নম্বর: ${a.vehicleNo || '—'}`, bn(a.phone), a.areaLabel || a.district),
        a.area || a.fare ? meta(a.area ? `এলাকা: ${a.area}` : null, a.fare ? `ভাড়া: ${a.fare}` : null) : null,
      ],
      [
        actionBtn('অনুমোদন', 'primary', () => act('admin_ambulance_resolve', { id: a.id, approve: true }), 'অ্যাম্বুলেন্স অনুমোদন করা হয়েছে', done),
        actionBtn('বাতিল', 'warn', () => act('admin_ambulance_resolve', { id: a.id, approve: false }), 'অ্যাম্বুলেন্স বাতিল করা হয়েছে', done),
      ],
    );
  }

  function hospitalRow(r) {
    const done = () => dropItem('hospitalRequests', (x) => x.id === r.id);
    const dups = (r.possibleDuplicates || []);
    return item(
      [
        h('div', { class: 'title' }, r.nameEn || r.nameBn || '—', r.count > 1 ? h('span', { class: 'chip alert', text: `${bn(r.count)} জন চেয়েছেন` }) : null),
        r.nameBn && r.nameEn ? h('div', null, r.nameBn) : null,
        meta(HOSPITAL_TYPE[r.type] || r.type, r.areaLabel || r.district),
        r.address || r.phone ? meta(r.address, r.phone ? bn(r.phone) : null) : null,
        dups.length
          ? h('div', { class: 'dups' },
            h('p', { text: 'তালিকায় একই রকম নামের হাসপাতাল আছে। একই হলে মিলিয়ে দিন:' }),
            dups.map((d) => h('div', null,
              h('span', null, [d.nameBn || d.nameEn, d.areaLabel].filter(Boolean).join(', ')),
              actionBtn('মেলান', 'quiet', () => act('admin_hospital_resolve', { id: r.id, op: 'merge', targetId: d.id }), 'অনুরোধটা আগের হাসপাতালের সাথে মেলানো হয়েছে', done))))
          : null,
      ],
      [
        actionBtn('অনুমোদন', 'primary', () => act('admin_hospital_resolve', { id: r.id, op: 'approve' }), 'হাসপাতাল তালিকায় যোগ হয়েছে', done),
        actionBtn('বাতিল', 'warn', () => act('admin_hospital_resolve', { id: r.id, op: 'reject' }), 'অনুরোধ বাতিল করা হয়েছে', done),
      ],
    );
  }

  function reportRow(r) {
    const done = () => dropItem('reports', (x) => x.targetPath === r.targetPath && x.reportedUid === r.reportedUid);
    const kind = r.kind === 'homevisit'
      ? (r.reportedRole === 'patient' ? 'হোম ভিজিট: রোগী' : 'হোম ভিজিট: নার্স')
      : REPORT_KIND[r.kind] || r.kind;
    const removeLabel = r.kind === 'homevisit' ? 'হোম ভিজিট থেকে বাদ দিন'
      : r.kind === 'ambulance' || r.kind === 'pharmacy' ? 'বন্ধ করুন' : 'পোস্ট মুছুন';
    const removedMsg = r.kind === 'homevisit' ? 'হোম ভিজিট থেকে বাদ দেওয়া হয়েছে'
      : r.kind === 'ambulance' || r.kind === 'pharmacy' ? 'বন্ধ করা হয়েছে' : 'পোস্ট মুছে ফেলা হয়েছে';
    // প্রশ্ন/উত্তর/টিপসের লেখায় কারণগুলো থাকে না, তাই আলাদা দেখানো
    const showReasons = ['question', 'answer', 'tip'].includes(r.kind) && (r.reasons || []).length;
    const body = { targetPath: r.targetPath, ...(r.reportedUid ? { reportedUid: r.reportedUid } : {}) };
    return item(
      [
        h('div', { class: 'title' }, kind, h('span', { class: 'chip alert', text: `${bn(r.count)}টি রিপোর্ট` })),
        r.authorName ? meta(r.authorName) : null,
        h('p', { class: 'body', text: r.text || '' }),
        showReasons ? h('ul', { class: 'reasons' }, r.reasons.map((x) => h('li', { text: x }))) : null,
      ],
      [
        actionBtn(removeLabel, 'warn', () => act('admin_report_resolve', { ...body, remove: true }), removedMsg, done),
        actionBtn('রেখে দিন', 'quiet', () => act('admin_report_resolve', { ...body, remove: false }), 'রেখে দেওয়া হয়েছে', done),
      ],
    );
  }

  function correctionRow(c) {
    const done = () => dropItem('corrections', (x) => x.id === c.id);
    return item(
      [
        h('div', { class: 'title' }, c.name || '—'),
        meta(`এখনকার তারিখ: ${fmtDate(c.currentMillis)}`, `চাওয়া তারিখ: ${c.requestedMillis ? fmtDate(c.requestedMillis) : 'কখনো দেননি'}`),
        c.reason ? h('p', { class: 'body', text: `কারণ: ${c.reason}` }) : null,
      ],
      [
        actionBtn('অনুমোদন', 'primary', () => post('/api/admin/resolve', { id: c.id, approve: true }), 'তারিখ সংশোধন করা হয়েছে', done),
        actionBtn('বাতিল', 'warn', () => post('/api/admin/resolve', { id: c.id, approve: false }), 'অনুরোধ বাতিল করা হয়েছে', done),
      ],
    );
  }

  function adminsSection() {
    const a = data.admins;
    if (!a) return null;
    const list = h('div', { class: 'list admins' });
    (a.owners || []).forEach((e) => list.append(item([h('div', { class: 'title' }, e, h('span', { class: 'chip', text: 'মালিক' }))], [])));
    (a.items || []).forEach(({ email }) => list.append(item(
      [h('div', { class: 'title' }, email)],
      [actionBtn('বাদ দিন', 'warn', () => act('admin_remove', { email }), 'অ্যাডমিন থেকে বাদ দেওয়া হয়েছে',
        () => { a.items = a.items.filter((x) => x.email !== email); })],
    )));
    const input = h('input', { type: 'email', placeholder: 'নতুন অ্যাডমিনের ইমেইল', 'aria-label': 'নতুন অ্যাডমিনের ইমেইল', maxlength: 200 });
    const add = actionBtn('যোগ করুন', 'primary', async () => {
      const email = input.value.trim().toLowerCase();
      if (!email.includes('@')) throw new Error('সঠিক ইমেইল দিন');
      await act('admin_add', { email });
      add.newEmail = email;
    }, 'অ্যাডমিন যোগ হয়েছে', () => { a.items = [...(a.items || []), { email: add.newEmail }]; });
    list.append(h('div', { class: 'add' }, input, add));
    return h('section', { class: 'section', id: 'admins' },
      h('h2', null, 'অ্যাডমিন'),
      h('p', { class: 'note', text: 'অ্যাডমিনরা সব আবেদন আর রিপোর্ট দেখতে পারেন। যোগ বা বাদ শুধু মালিক করতে পারেন। তাদের এই ইমেইল দিয়েই লগইন করতে হবে।' }),
      list);
  }

  // ---------------- আঁকা ----------------
  function freshText() {
    const m = Math.floor((Date.now() - loadedAt) / 60000);
    return m < 1 ? 'এইমাত্র হালনাগাদ' : `${bn(m)} মিনিট আগে হালনাগাদ`;
  }

  function render() {
    if (!data) return;
    const counts = SECTIONS.map((s) => itemsOf(s.key).length);
    const total = counts.reduce((a, b) => a + b, 0);

    const headline = total
      ? h('h1', null, 'অপেক্ষায় ', h('span', { class: 'n', text: bn(total) }), 'টা কাজ')
      : h('h1', { class: 'clear', text: 'সব কাজ শেষ' });

    const queues = h('ul', { class: 'queues', 'aria-label': 'কোন অংশে কতটা' },
      SECTIONS.map((s, i) => h('li', null,
        h('button', {
          type: 'button',
          class: counts[i] ? 'has' : 'zero',
          onclick: () => document.getElementById(s.key).scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }),
        }, h('span', { class: 'count', text: bn(counts[i]) }), h('span', { text: s.short })))));

    const fresh = h('div', { class: 'fresh' },
      h('span', { id: 'fresh', text: freshText() }),
      h('button', { type: 'button', class: 'btn quiet', onclick: () => refresh(false), text: 'রিফ্রেশ' }));

    const sections = SECTIONS.map((s, i) => {
      const part = data[s.key] || {};
      const items = itemsOf(s.key);
      return h('section', { class: `section${items.length ? '' : ' empty'}`, id: s.key },
        h('h2', null, s.title, h('span', { class: 'c', text: items.length ? `${bn(items.length)}টা` : '' })),
        part.error ? h('p', { class: 'part-error', role: 'alert', text: `এই অংশ আনা যায়নি: ${part.error}` }) : null,
        items.length ? h('div', { class: 'list' }, items.map((x) => s.row(x))) : h('p', { class: 'empty-line', text: 'অপেক্ষায় কিছু নেই।' }));
    });

    app.replaceChildren(
      h('div', { class: 'summary' }, h('div', { class: 'headline' }, headline, fresh), queues),
      ...sections,
      ...[adminsSection()].filter(Boolean),
    );
  }

  // ---------------- সময় ধরে কাজ ----------------
  ['click', 'keydown', 'pointermove', 'scroll', 'touchstart'].forEach((ev) =>
    window.addEventListener(ev, () => { lastActive = Date.now(); }, { passive: true }));

  setInterval(() => {
    if (!loggedIn) return;
    if (Date.now() - lastActive > IDLE_MS) {
      logout('৩০ মিনিট কিছু না করায় লগআউট হয়েছে। আবার লগইন করুন।');
      return;
    }
    const f = document.getElementById('fresh');
    if (f) f.textContent = freshText();
    if (!document.hidden && !busy && Date.now() - loadedAt > AUTO_REFRESH_MS) refresh(false);
  }, 30000);

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && loggedIn && !busy && Date.now() - loadedAt > 2 * 60 * 1000) refresh(false);
  });

  // শুরু: কুকি থাকলে সরাসরি তালিকা, না থাকলে লগইন
  refresh(true);
})();
