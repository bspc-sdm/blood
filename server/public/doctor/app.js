// Lifolo ডাক্তারের ওয়েব: প্রেসক্রিপশন লেখা আর ছাপা, রোগী এন্ট্রি (ডাক্তার ও সহকারী)।
// নিরাপত্তা: ব্যবহারকারীর লেখা সবসময় textContent দিয়ে বসে (innerHTML নয়); লগইন httpOnly কুকিতে।
(function () {
  'use strict';

  const root = document.getElementById('root');
  const toastEl = document.getElementById('toast');
  const ICONS = window.LF_ICONS || {};
  const BN = '০১২৩৪৫৬৭৮৯';
  const bn = (v) => String(v ?? '').replace(/[0-9]/g, (d) => BN[d]);
  const SVGNS = 'http://www.w3.org/2000/svg';

  const S = { me: null, role: null, roles: [], doctorUid: null, doctorName: '', chambers: [], view: 'write', target: null, open: null, rids: [] };
  const ROLE_LABEL = { doctor: 'ডাক্তার', assistant: 'সহকারী', pharmacy: 'ফার্মেসি', lab: 'প্যাথলজি' };
  const FIRST_VIEW = { doctor: 'write', assistant: 'patients', pharmacy: 'dispense', lab: 'tests' };

  // ---------- ছোট সাহায্যকারী ----------
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) continue;
        if (k === 'class') el.setAttribute('class', v);
        else if (k === 'text') el.textContent = v;
        else if (k === 'value') el.value = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : String(v));
      }
    }
    for (const kid of kids.flat(3)) {
      if (kid == null || kid === false || kid === '') continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }
  function icon(name) {
    const svg = document.createElementNS(SVGNS, 'svg');
    svg.setAttribute('class', 'ic');
    svg.setAttribute('viewBox', '0 0 960 960');
    svg.setAttribute('aria-hidden', 'true');
    const p = document.createElementNS(SVGNS, 'path');
    p.setAttribute('d', ICONS[name] || '');
    svg.append(p);
    return svg;
  }
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toast.t);
    toast.t = setTimeout(() => { toastEl.hidden = true; }, 3200);
  }
  const fmtDate = (ms) => (ms ? new Intl.DateTimeFormat('bn-BD', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(ms)) : '—');
  // অ্যাপয়েন্টমেন্ট/আবার দেখানোর তারিখ UTC মধ্যরাত
  const fmtDay = (ms) => (ms ? new Intl.DateTimeFormat('bn-BD', { timeZone: 'UTC', day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(ms)) : '—');
  const GENDER = { male: 'পুরুষ', female: 'মহিলা' };
  const TIMING = { before: 'খাবারের আগে', after: 'খাবারের পরে' };
  const FIND = { bp: 'প্রেসার', pulse: 'পালস', temp: 'তাপমাত্রা', weight: 'ওজন', spo2: 'SpO₂' };
  const DOSES = ['1+0+1', '1+0+0', '0+0+1', '1+1+1', '0+1+0', '1+1+1+1'];
  const TESTS = ['CBC', 'RBS', 'FBS', 'HbA1c', 'Lipid profile', 'S. Creatinine', 'SGPT', 'Urine R/E', 'X-ray chest P/A', 'ECG', 'USG of whole abdomen', 'TSH'];
  function formShort(f) {
    const l = String(f || '').toLowerCase();
    if (!l) return '';
    if (l.includes('tablet')) return 'Tab.';
    if (l.includes('capsule')) return 'Cap.';
    if (l.includes('syrup') || l.includes('suspension') || l.includes('oral solution')) return 'Syr.';
    if (l.includes('injection') || l.includes('infusion')) return 'Inj.';
    if (l.includes('drop')) return 'Drop';
    return f;
  }

  // ---------- সার্ভার ----------
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
      throw new ApiError('ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না।', 0);
    }
    let json = {};
    try { json = await res.json(); } catch (e) { /* খালি */ }
    if (!res.ok) {
      const err = new ApiError(json.error || 'সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন', res.status, json.code);
      if (S.me && (res.status === 401 || json.code === 'session_expired')) showLogin(err.message);
      throw err;
    }
    return json;
  }
  const act = (action, body) => post('/api/community', { action, ...body });
  // সহকারী হলে কোন ডাক্তারের হয়ে
  const forDoctor = () => (S.role === 'assistant' ? { doctorUid: S.doctorUid } : {});

  // ---------- লগইন ----------
  function showLogin(message) {
    S.me = null;
    let stage = 'email';
    let email = '';
    try { email = localStorage.getItem('lf_doc_email') || ''; } catch (e) { /* না থাকলেও চলে */ }
    let error = message || '';
    let sending = false;
    function draw() {
      const form = h('form', { class: 'card login', novalidate: true, onsubmit: submit },
        h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), h('span', { text: 'ডাক্তার' })),
        h('h1', { text: 'লগইন' }),
        h('p', { text: stage === 'code' ? `কোড গেছে: ${email}` : 'যাচাই করা ডাক্তার বা ডাক্তারের সহকারী — অ্যাপের ইমেইলটাই দিন।' }),
        error ? h('p', { class: 'error', role: 'alert', text: error }) : null,
        stage === 'email'
          ? [h('label', { class: 'field' }, h('span', { text: 'ইমেইল' }), h('input', { type: 'email', name: 'email', autocomplete: 'email', required: true, value: email })),
            h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'পাঠানো হচ্ছে…' : 'কোড পাঠান' }))]
          : [h('label', { class: 'field' }, h('span', { text: 'কোড' }), h('input', { class: 'code-input', name: 'code', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, required: true })),
            h('div', { class: 'row' },
              h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'যাচাই হচ্ছে…' : 'লগইন করুন' }),
              h('button', { class: 'link', type: 'button', onclick: () => { stage = 'email'; error = ''; draw(); }, text: 'ইমেইল বদলান' }))]);
      root.replaceChildren(h('div', { class: 'login-wrap' }, form));
      const first = form.querySelector('input');
      if (first) first.focus();
    }
    async function submit(ev) {
      ev.preventDefault();
      if (sending) return;
      const input = ev.target.querySelector('input');
      error = '';
      sending = true;
      draw();
      try {
        if (stage === 'email') {
          email = input.value.trim().toLowerCase();
          if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Error('সঠিক ইমেইল দিন');
          const r = await post('/api/auth/send-code', { email });
          S.rids = r && r.rid ? [r.rid] : [];
          try { localStorage.setItem('lf_doc_email', email); } catch (e) { /* না থাকলেও চলে */ }
          stage = 'code';
        } else {
          const code = input.value.replace(/[০-৯]/g, (d) => String(BN.indexOf(d))).replace(/\D/g, '');
          if (code.length !== 6) throw new Error('৬ সংখ্যার কোড দিন');
          await post('/api/auth/verify-code', { email, code, web: true, panel: 'doctor', ...(S.rids.length ? { rids: S.rids } : {}) });
          sending = false;
          await start();
          return;
        }
      } catch (e) { error = e.message; }
      sending = false;
      draw();
    }
    draw();
  }

  async function start() {
    try {
      const me = await act('web_me');
      const roles = [me.doctor ? 'doctor' : null, (me.assisting || []).length ? 'assistant' : null, me.pharmacy ? 'pharmacy' : null, me.lab ? 'lab' : null].filter(Boolean);
      if (!roles.length) throw new ApiError('এই ইমেইল কোনো যাচাই করা ডাক্তার, সহকারী, অনুমোদিত ফার্মেসি বা প্যাথলজির নয়', 403);
      S.me = me;
      S.roles = roles;
      if (me.doctor) {
        const r = await act('rx_me');
        S.ownName = r.name;
        S.chambers = r.chambers || [];
      }
      await setRole(roles[0]);
    } catch (e) {
      showLogin(e.status === 401 ? '' : e.message);
    }
  }

  async function setRole(role) {
    S.role = role;
    S.open = null;
    S.target = null;
    S.view = FIRST_VIEW[role];
    if (role === 'doctor') { S.doctorUid = null; S.doctorName = S.ownName || ''; }
    if (role === 'assistant') { S.doctorUid = S.me.assisting[0].doctorUid; S.doctorName = S.me.assisting[0].name; }
    if (role === 'pharmacy') S.doctorName = S.me.pharmacy.name;
    if (role === 'lab') S.doctorName = S.me.lab.name;
    render();
  }

  // ---------- কাঠামো ----------
  function render() {
    const tabs = {
      doctor: [['write', 'নতুন প্রেসক্রিপশন'], ['list', 'আমার প্রেসক্রিপশন'], ['reports', 'রিপোর্ট'], ['patients', 'রোগী'], ['team', 'সহকারী']],
      assistant: [['patients', 'রোগী'], ['payments', 'টাকা মেলানো']],
      pharmacy: [['dispense', 'প্রেসক্রিপশনের ওষুধ'], ['stock', 'স্টক']],
      lab: [['tests', 'পরীক্ষা ও রিপোর্ট']],
    }[S.role];
    const top = h('header', { class: 'doc-top' },
      h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), S.roles.length > 1 ? null : h('span', { text: ROLE_LABEL[S.role] })),
      S.roles.length > 1 ? h('select', { class: 'cell-input', style: 'max-width:160px', onchange: (e) => setRole(e.target.value) },
        S.roles.map((r) => h('option', { value: r, selected: r === S.role, text: ROLE_LABEL[r] }))) : null,
      h('nav', { class: 'seg' }, tabs.map(([k, label]) => h('a', { href: '#', class: S.view === k ? 'on' : null, text: label, onclick: (e) => { e.preventDefault(); S.view = k; S.open = null; render(); } }))),
      S.role === 'assistant' && S.me.assisting.length > 1
        ? h('select', { class: 'cell-input', style: 'max-width:240px', onchange: (e) => { const d = S.me.assisting[e.target.selectedIndex]; S.doctorUid = d.doctorUid; S.doctorName = d.name; render(); } },
          S.me.assisting.map((d) => h('option', { selected: d.doctorUid === S.doctorUid, text: d.name })))
        : null,
      h('div', { class: 'who' }, h('span', { text: S.role === 'assistant' ? `${S.doctorName}-এর সহকারী` : S.doctorName }),
        h('button', { class: 'btn quiet', type: 'button', onclick: logout, text: 'লগআউট' })));
    const page = h('main', { class: 'doc-page' });
    root.replaceChildren(top, page);
    if (S.open) return paperView(page, S.open);
    if (S.view === 'write') return writeView(page);
    if (S.view === 'list') return listView(page);
    if (S.view === 'team') return teamView(page);
    if (S.view === 'reports') return reviewsView(page, true);
    if (S.view === 'payments') return reviewsView(page, false);
    if (S.view === 'dispense') return dispenseView(page);
    if (S.view === 'stock') return stockView(page);
    if (S.view === 'tests') return testsView(page);
    return patientsView(page, null);
  }

  async function logout() {
    try { await act('web_logout'); } catch (e) { /* তবুও লগইন পাতা */ }
    showLogin('');
  }

  // ---------- রোগীর তালিকা (বাছাই/এন্ট্রি) ----------
  async function patientsView(page, onPick) {
    const wrap = h('div', { class: 'grid' });
    page.append(wrap);
    wrap.append(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    let data;
    try {
      data = await act('rx_patients', forDoctor());
    } catch (e) {
      wrap.replaceChildren(h('p', { class: 'error', text: e.message }));
      return;
    }
    let q = '';
    const list = h('div', { class: 'plist' });
    function drawList() {
      const items = [];
      if (data.appointments.length) {
        items.push(h('div', { class: 'block-title', text: 'অ্যাপের অ্যাপয়েন্টমেন্ট' }));
        data.appointments.forEach((a) => items.push(h('button', { class: 'pitem', type: 'button', onclick: onPick ? () => onPick({ appointmentId: a.id, name: a.name }) : null },
          icon('event'), h('div', null, h('b', { text: a.name || '—' }), h('small', { text: [fmtDay(a.date), a.serial ? `সিরিয়াল ${bn(a.serial)}` : '', a.chamber].filter(Boolean).join(' · ') })))));
      }
      items.push(h('div', { class: 'block-title', text: 'এন্ট্রি করা রোগী' }));
      const shown = data.patients.filter((p) => !q || p.name.toLowerCase().includes(q) || p.phone.includes(q));
      if (!shown.length) items.push(h('p', { class: 'note', text: 'কেউ নেই' }));
      shown.forEach((p) => items.push(h('button', { class: 'pitem', type: 'button', onclick: onPick ? () => onPick({ rxPatientId: p.id, name: p.name, age: p.age, gender: p.gender }) : null },
        icon('person'), h('div', null, h('b', { text: p.name }), h('small', { text: [p.age != null ? `${bn(p.age)} বছর` : '', GENDER[p.gender] || '', bn(p.phone), p.linked ? 'অ্যাপে আছেন' : ''].filter(Boolean).join(' · ') })))));
      list.replaceChildren(...items);
    }
    const search = h('div', { class: 'search' }, icon('search'), h('input', { placeholder: 'নাম বা ফোন দিয়ে খুঁজুন', oninput: (e) => { q = e.target.value.trim().toLowerCase(); drawList(); } }));
    // নতুন রোগী
    const add = h('form', { class: 'card pad grid', onsubmit: async (e) => {
      e.preventDefault();
      const f = e.target;
      const btn = f.querySelector('button');
      btn.disabled = true;
      try {
        const age = f.age.value.trim();
        const r = await act('rx_patient_add', { ...forDoctor(), name: f.pname.value.trim(), age: age ? Number(age) : null, gender: f.gender.value, phone: f.phone.value.trim() });
        toast(r.existed ? 'এই রোগী আগে থেকেই ছিলেন' : 'রোগী যোগ হয়েছে');
        f.reset();
        data.patients = [r.patient, ...data.patients.filter((p) => p.id !== r.patient.id)];
        drawList();
        if (onPick) onPick({ rxPatientId: r.patient.id, name: r.patient.name, age: r.patient.age, gender: r.patient.gender });
      } catch (err) { toast(err.message); }
      btn.disabled = false;
    } },
    h('b', { text: 'নতুন রোগী' }),
    h('div', { class: 'form-grid' },
      h('label', { class: 'f' }, h('span', { text: 'নাম' }), h('input', { name: 'pname', required: true, maxlength: 80 })),
      h('label', { class: 'f' }, h('span', { text: 'বয়স' }), h('input', { name: 'age', inputmode: 'numeric', maxlength: 3 })),
      h('label', { class: 'f' }, h('span', { text: 'লিঙ্গ' }), h('select', { name: 'gender' }, h('option', { value: '', text: '—' }), h('option', { value: 'male', text: 'পুরুষ' }), h('option', { value: 'female', text: 'মহিলা' }))),
      h('label', { class: 'f' }, h('span', { text: 'মোবাইল' }), h('input', { name: 'phone', required: true, inputmode: 'tel', maxlength: 20 }))),
    h('p', { class: 'note', text: 'রোগী এই নম্বর আর প্রেসক্রিপশনের কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।' }),
    h('div', null, h('button', { class: 'btn primary', type: 'submit', text: onPick ? 'যোগ করে লিখুন' : 'যোগ করুন' })));
    wrap.replaceChildren(add, search, list);
    drawList();
  }

  // ---------- লেখা ----------
  function writeView(page) {
    if (!S.target) {
      page.append(h('h2', { text: 'কার জন্য লিখবেন?' }));
      return patientsView(page, (t) => { S.target = t; render(); });
    }
    const t = S.target;
    const meds = [];
    const tests = new Set();
    const form = h('form', { class: 'card pad', onsubmit: (e) => e.preventDefault() });
    const medBody = h('tbody');
    const medList = h('datalist', { id: 'drugs' });
    const doseList = h('datalist', { id: 'doses' }, DOSES.map((d) => h('option', { value: d })));
    let drugTimer = null;
    function addMed(m) {
      const row = { name: '', form: '', strength: '', dose: '1+0+1', timing: 'after', days: 5, note: '', ...(m || {}) };
      meds.push(row);
      const tr = h('tr');
      const nameIn = h('input', { list: 'drugs', value: row.name, placeholder: 'ওষুধের নাম', oninput: (e) => {
        row.name = e.target.value;
        // তালিকার নাম বাছলে ধরন আর জেনেরিক বসে যায়
        const hit = (medList._items || []).find((d) => d.name === row.name);
        if (hit) { row.form = hit.form; row.strength = hit.generic; formIn.value = hit.form; strIn.value = hit.generic; }
        clearTimeout(drugTimer);
        const qv = row.name.trim();
        if (qv.length < 2) return;
        drugTimer = setTimeout(async () => {
          try {
            const r = await act('drug_search', { q: qv });
            medList._items = r.items || [];
            medList.replaceChildren(...medList._items.map((d) => h('option', { value: d.name, text: [d.generic, d.form, d.company].filter(Boolean).join(' · ') })));
          } catch (err) { /* খোঁজা না গেলেও লেখা যায় */ }
        }, 300);
      } });
      const formIn = h('input', { value: row.form, placeholder: 'Tablet', oninput: (e) => { row.form = e.target.value; } });
      const strIn = h('input', { value: row.strength, placeholder: '৫০০ mg', oninput: (e) => { row.strength = e.target.value; } });
      tr.append(
        h('td', null, nameIn),
        h('td', null, formIn),
        h('td', null, strIn),
        h('td', null, h('input', { list: 'doses', value: row.dose, oninput: (e) => { row.dose = e.target.value.replace(/\s/g, ''); } })),
        h('td', null, h('select', { onchange: (e) => { row.timing = e.target.value; } }, ['after', 'before', ''].map((v) => h('option', { value: v, selected: row.timing === v, text: TIMING[v] || '—' })))),
        h('td', null, h('input', { value: String(row.days), inputmode: 'numeric', title: '০ = চলবে', oninput: (e) => { row.days = Number(e.target.value.replace(/\D/g, '')) || 0; } })),
        h('td', null, h('input', { value: row.note, placeholder: 'নোট', oninput: (e) => { row.note = e.target.value; } })),
        h('td', null, h('button', { class: 'icon-btn', type: 'button', title: 'সরান', onclick: () => { meds.splice(meds.indexOf(row), 1); tr.remove(); } }, icon('close'))));
      medBody.append(tr);
      nameIn.focus();
    }
    const field = (label, el) => h('label', { class: 'f' }, h('span', { text: label }), el);
    const nameIn = h('input', { value: t.name || '', maxlength: 80 });
    const ageIn = h('input', { value: t.age != null ? String(t.age) : '', inputmode: 'numeric', maxlength: 3 });
    const genderIn = h('select', null, ['', 'male', 'female'].map((v) => h('option', { value: v, selected: (t.gender || '') === v, text: GENDER[v] || '—' })));
    const complaints = h('textarea', { maxlength: 1000 });
    const finds = Object.fromEntries(Object.keys(FIND).map((k) => [k, h('input', { maxlength: 20 })]));
    const diagnosis = h('input', { maxlength: 500 });
    const testPlace = h('input', { maxlength: 200, placeholder: 'যেমন: পপুলার ডায়াগনস্টিক, ময়মনসিংহ' });
    const otherTest = h('input', { maxlength: 80, placeholder: 'অন্য পরীক্ষা' });
    const testBox = h('div', { class: 'checks' });
    function drawTests() {
      const all = [...TESTS, ...[...tests].filter((x) => !TESTS.includes(x))];
      testBox.replaceChildren(...all.map((x) => h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: tests.has(x), onchange: (e) => { if (e.target.checked) tests.add(x); else tests.delete(x); } }), x)));
    }
    drawTests();
    const advice = h('textarea', { maxlength: 1000 });
    const follow = h('select', null, [0, 3, 7, 15, 30].map((d) => h('option', { value: String(d), text: d ? `${bn(d)} দিন পর` : 'দরকার নেই' })));
    const followNote = h('input', { maxlength: 80, placeholder: 'যেমন: রিপোর্ট নিয়ে আসবেন' });
    const chamber = S.chambers.length ? h('select', null, S.chambers.map((c, i) => h('option', { value: String(i), text: c }))) : null;
    const err = h('p', { class: 'error', role: 'alert' });
    const submit = h('button', { class: 'btn primary', type: 'button', text: 'প্রেসক্রিপশন দিন' });
    let armed = false;
    submit.addEventListener('click', async () => {
      err.textContent = '';
      // দুবার চাপলে তবেই — দেওয়ার পর আর বদলানো যায় না
      if (!armed) {
        armed = true;
        submit.textContent = 'নিশ্চিত? আবার চাপুন (পরে বদলানো যাবে না)';
        submit.classList.add('warn', 'armed');
        setTimeout(() => { armed = false; submit.textContent = 'প্রেসক্রিপশন দিন'; submit.classList.remove('warn', 'armed'); }, 6000);
        return;
      }
      submit.disabled = true;
      try {
        const body = {
          ...(t.appointmentId ? { appointmentId: t.appointmentId } : { rxPatientId: t.rxPatientId }),
          patient: { name: nameIn.value.trim(), age: ageIn.value.trim() ? Number(ageIn.value.trim()) : null, gender: genderIn.value },
          complaints: complaints.value,
          findings: Object.fromEntries(Object.entries(finds).map(([k, el]) => [k, el.value.trim()])),
          diagnosis: diagnosis.value,
          medicines: meds.filter((m) => m.name.trim()).map((m) => ({ ...m, name: m.name.trim(), days: Math.min(365, Math.max(0, m.days | 0)) })),
          tests: [...tests, ...(otherTest.value.trim() ? [otherTest.value.trim()] : [])],
          testPlace: testPlace.value,
          advice: advice.value,
          followUpDays: Number(follow.value),
          followUpNote: followNote.value,
          ...(chamber ? { chamberIndex: Number(chamber.value) } : {}),
        };
        const r = await act('rx_create', body);
        const got = await act('rx_get', { id: r.id });
        S.target = null;
        S.open = got.item;
        toast('প্রেসক্রিপশন দেওয়া হয়েছে');
        render();
        return;
      } catch (e) { err.textContent = e.message; }
      submit.disabled = false;
    });
    form.append(
      h('div', { class: 'toolbar' }, h('b', { text: 'রোগী' }), h('button', { class: 'link', type: 'button', text: 'অন্য রোগী', onclick: () => { S.target = null; render(); } })),
      h('div', { class: 'form-grid' }, field('নাম', nameIn), field('বয়স', ageIn), field('লিঙ্গ', genderIn)),
      h('div', { class: 'block-title', text: 'সমস্যা ও পরীক্ষায় পাওয়া' }),
      field('সমস্যা', complaints),
      h('div', { class: 'form-grid', style: 'margin-top:10px' }, Object.entries(FIND).map(([k, label]) => field(label, finds[k]))),
      h('div', { style: 'margin-top:10px' }, field('রোগনির্ণয়', diagnosis)),
      h('div', { class: 'rxsign block-title', text: '℞ ওষুধ' }),
      h('div', { class: 'table-wrap' }, h('table', { class: 'meds' },
        h('thead', null, h('tr', null, ['নাম', 'ধরন', 'মাত্রা', 'নিয়ম', 'খাবার', 'দিন', 'নোট', ''].map((x) => h('th', { text: x })))),
        medBody)),
      medList, doseList,
      h('button', { class: 'btn tonal', type: 'button', onclick: () => addMed() }, icon('medication'), 'ওষুধ যোগ করুন'),
      h('div', { class: 'block-title', text: 'পরীক্ষা' }),
      testBox,
      h('div', { class: 'field-row' }, otherTest, h('button', { class: 'btn quiet', type: 'button', text: 'যোগ', onclick: () => { const v = otherTest.value.trim(); if (v) { tests.add(v); otherTest.value = ''; drawTests(); } } })),
      h('div', { style: 'margin-top:10px' }, field('কোথায় করাবেন (ঐচ্ছিক — রোগী চাইলে অন্য জায়গাতেও করাতে পারবেন)', testPlace)),
      h('div', { class: 'block-title', text: 'পরামর্শ' }),
      field('পরামর্শ', advice),
      h('div', { class: 'form-grid', style: 'margin-top:10px' }, field('আবার দেখাবেন', follow), field('নোট', followNote), chamber ? field('চেম্বার', chamber) : null),
      err,
      h('div', { class: 'reply-actions' }, submit));
    page.append(form);
    addMed();
  }

  // ---------- কাগজ (দেখা, ছাপা, বাতিল) ----------
  function paperView(page, rx) {
    const d = rx.doctor || {};
    const p = rx.patient || {};
    const f = rx.findings || {};
    const sec = (title, body) => (body ? h('div', { class: 'sec' }, h('b', { text: title }), h('div', { style: 'white-space:pre-wrap', text: body })) : null);
    const paper = h('article', { class: 'paper' },
      h('div', { class: 'ph' },
        h('div', null,
          h('h2', { text: d.name || '' }),
          h('div', { class: 'muted', text: [d.degrees, d.regNo ? `BMDC ${d.regNo}` : ''].filter(Boolean).join(' · ') }),
          d.chamber ? h('div', { class: 'muted', text: [d.chamber.name, d.chamber.address, d.chamber.phone ? bn(d.chamber.phone) : ''].filter(Boolean).join(', ') }) : null),
        h('div', { style: 'text-align:center' }, h('img', { class: 'qr', src: `/api/share?rxqr=${encodeURIComponent(rx.code)}`, alt: 'যাচাইয়ের QR' }), h('div', { class: 'muted', text: rx.code }))),
      rx.status === 'cancelled' ? h('p', { class: 'cancelled', text: 'বাতিল করা প্রেসক্রিপশন' }) : null,
      h('div', { class: 'pt' }, h('span', { text: [p.name, p.age != null ? `${bn(p.age)} বছর` : '', GENDER[p.gender] || ''].filter(Boolean).join(' · ') }), h('span', { text: fmtDate(rx.createdAt) })),
      sec('সমস্যা', rx.complaints),
      sec('পরীক্ষায় পাওয়া', Object.entries(f).filter(([, v]) => v).map(([k, v]) => `${FIND[k] || k}: ${v}`).join(' · ')),
      sec('রোগনির্ণয়', rx.diagnosis),
      rx.medicines.length ? [h('div', { class: 'rxsign', text: '℞' }), h('ol', null, rx.medicines.map((m) => h('li', null,
        h('b', { text: `${formShort(m.form)} ${m.name}${m.strength ? ` (${m.strength})` : ''}`.trim() }),
        h('div', { class: 'how', text: [bn(m.dose), TIMING[m.timing] || '', m.days ? `${bn(m.days)} দিন` : 'চলবে', m.note].filter(Boolean).join(' · ') }))))] : null,
      sec('পরীক্ষা', rx.tests.map((x) => `• ${x}`).join('\n')),
      rx.testPlace ? h('div', { class: 'muted', text: `করাতে পারেন: ${rx.testPlace} (চাইলে অন্য জায়গাতেও)` }) : null,
      sec('পরামর্শ', rx.advice),
      rx.followUpDate ? sec('আবার দেখাবেন', `${fmtDay(rx.followUpDate)}${rx.followUpNote ? ` · ${rx.followUpNote}` : ''}`) : null,
      h('div', { class: 'foot', text: 'Lifolo-তে দেওয়া প্রেসক্রিপশন। আসল কি না দেখতে QR স্ক্যান করুন।' }));
    const cancel = h('button', { class: 'btn warn', type: 'button', text: 'বাতিল করুন' });
    let armed = false;
    cancel.addEventListener('click', async () => {
      if (!armed) { armed = true; cancel.classList.add('armed'); cancel.textContent = 'নিশ্চিত? আবার চাপুন'; return; }
      cancel.disabled = true;
      try {
        await act('rx_cancel', { id: rx.id });
        S.open = { ...rx, status: 'cancelled' };
        toast('বাতিল করা হয়েছে');
        render();
      } catch (e) { toast(e.message); cancel.disabled = false; }
    });
    page.append(
      h('div', { class: 'toolbar' },
        h('button', { class: 'btn quiet', type: 'button', text: '← ফিরে যান', onclick: () => { S.open = null; render(); } }),
        h('button', { class: 'btn primary', type: 'button', onclick: () => window.print() }, 'ছাপুন / PDF'),
        S.role === 'doctor' && rx.status !== 'cancelled' ? cancel : null),
      paper);
  }

  // ---------- আমার প্রেসক্রিপশন ----------
  async function listView(page) {
    page.append(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    try {
      const r = await act('rx_doctor_list');
      const rows = r.items.map((rx) => h('tr', { class: 'click', onclick: () => { S.open = rx; render(); } },
        h('td', { 'data-label': 'তারিখ', text: fmtDate(rx.createdAt) }),
        h('td', { class: 'main-cell', 'data-label': 'রোগী', text: rx.patient.name || '—' }),
        h('td', { 'data-label': 'ওষুধ', text: rx.medicines.map((m) => m.name).slice(0, 4).join(', ') }),
        h('td', { 'data-label': 'অবস্থা' }, h('span', { class: `chip ${rx.status === 'cancelled' ? 'bad' : 'good'}`, text: rx.status === 'cancelled' ? 'বাতিল' : 'চালু' }), rx.linked ? h('span', { class: 'chip pri', style: 'margin-left:4px', text: 'অ্যাপে' }) : null)));
      page.replaceChildren(rows.length
        ? h('div', { class: 'card table-wrap' }, h('table', { class: 't' }, h('thead', null, h('tr', null, ['তারিখ', 'রোগী', 'ওষুধ', 'অবস্থা'].map((x) => h('th', { text: x })))), h('tbody', null, rows)))
        : h('div', { class: 'card empty', text: 'এখনো কোনো প্রেসক্রিপশন নেই' }));
    } catch (e) {
      page.replaceChildren(h('p', { class: 'error', text: e.message }));
    }
  }

  // ---------- সহকারী ----------
  async function teamView(page) {
    let emails = [];
    try { emails = (await act('rx_team')).emails; } catch (e) { page.append(h('p', { class: 'error', text: e.message })); return; }
    const list = h('div', { class: 'grid' });
    function draw() {
      list.replaceChildren(...(emails.length ? emails.map((e) => h('div', { class: 'ctl' }, h('div', { class: 'what', text: e }),
        h('button', { class: 'btn warn', type: 'button', text: 'সরান', onclick: () => save(emails.filter((x) => x !== e)) }))) : [h('p', { class: 'note', text: 'কোনো সহকারী নেই' })]));
    }
    async function save(next) {
      try { emails = (await act('rx_team_set', { emails: next })).emails; toast('সেভ হয়েছে'); draw(); } catch (e) { toast(e.message); }
    }
    const input = h('input', { type: 'email', placeholder: 'সহকারীর ইমেইল', maxlength: 200 });
    page.append(h('div', { class: 'card pad grid' },
      h('b', { text: 'সহকারী' }),
      h('p', { class: 'note', text: 'সহকারী এই ইমেইলে লগইন করে রোগী এন্ট্রি করবেন (অ্যাপে বা এই ওয়েবে)। প্রেসক্রিপশন লিখবেন শুধু আপনি। সর্বোচ্চ ৫ জন।' }),
      list,
      h('div', { class: 'field-row' }, input, h('button', { class: 'btn primary', type: 'button', text: 'যোগ করুন', onclick: () => { const v = input.value.trim().toLowerCase(); if (v) { input.value = ''; save([...emails, v]); } } }))));
    draw();
  }

  // ---------- রিপোর্ট দেখা (ডাক্তার) / টাকা মেলানো (ডাক্তার ও সহকারী) ----------
  const RV = { unpaid: 'টাকা দেওয়া হয়নি', claimed: 'টাকা পাঠিয়েছেন — মিলিয়ে দেখুন', paid: 'দেখার অপেক্ষায়', done: 'দেখা হয়েছে' };
  const PAY = { bkash: 'bKash', nagad: 'Nagad', rocket: 'Rocket' };

  async function reviewsView(page, asDoctor) {
    if (asDoctor) {
      const box = h('form', { class: 'card pad grid', onsubmit: (e) => e.preventDefault() }, h('b', { text: 'অনলাইনে রিপোর্ট দেখা' }), h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
      page.append(box);
      act('review_settings').then((st) => {
        const en = h('input', { type: 'checkbox', checked: st.enabled });
        const noFee = h('input', { type: 'checkbox', checked: st.noFee });
        const fee = h('input', { value: st.fee || '', maxlength: 40, placeholder: 'যেমন: ৩০০ টাকা' });
        const num = h('input', { value: st.payNumber || '', maxlength: 20, inputmode: 'tel' });
        const methods = Object.keys(PAY).map((m) => [m, h('input', { type: 'checkbox', checked: (st.payMethods || []).includes(m) })]);
        const save = h('button', { class: 'btn primary', type: 'button', text: 'সেভ করুন' });
        save.addEventListener('click', async () => {
          save.disabled = true;
          try {
            await act('review_settings', { set: true, enabled: en.checked, noFee: noFee.checked, fee: fee.value.trim(), payNumber: num.value.trim(), payMethods: methods.filter(([, c]) => c.checked).map(([m]) => m) });
            toast('সেভ হয়েছে');
          } catch (e) { toast(e.message); }
          save.disabled = false;
        });
        box.replaceChildren(h('b', { text: 'অনলাইনে রিপোর্ট দেখা' }),
          h('p', { class: 'note', text: 'রোগী আপনার নম্বরে সরাসরি টাকা পাঠিয়ে TrxID দেবেন; আপনি বা সহকারী মিলিয়ে নিশ্চিত করলে তবেই রিপোর্ট খুলবে। Lifolo টাকা রাখে না।' }),
          h('div', { class: 'checks' }, h('label', { class: 'check' }, en, 'চালু'), h('label', { class: 'check' }, noFee, 'ফি চাই না')),
          h('div', { class: 'form-grid' }, h('label', { class: 'f' }, h('span', { text: 'ফি' }), fee), h('label', { class: 'f' }, h('span', { text: 'টাকা নেওয়ার নম্বর' }), num)),
          h('div', { class: 'checks' }, methods.map(([m, c]) => h('label', { class: 'check' }, c, PAY[m]))),
          h('div', null, save));
      }).catch((e) => box.replaceChildren(h('p', { class: 'error', text: e.message })));
    }
    const listBox = h('div', { class: 'grid', style: 'margin-top:16px' }, h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    page.append(listBox);
    let items;
    try { items = (await act('reviews_doctor', forDoctor())).items; } catch (e) { listBox.replaceChildren(h('p', { class: 'error', text: e.message })); return; }
    if (!items.length) { listBox.replaceChildren(h('div', { class: 'card empty', text: 'এখনো কোনো রিপোর্ট নেই' })); return; }
    listBox.replaceChildren(...items.map((r) => {
      const actions = h('div', { class: 'reply-actions' });
      if (r.status === 'claimed') {
        actions.append(
          h('button', { class: 'btn primary', type: 'button', text: 'টাকা পেয়েছি', onclick: async () => { try { await act('review_confirm', { ...forDoctor(), id: r.id, ok: true }); toast('নিশ্চিত হয়েছে'); render(); } catch (e) { toast(e.message); } } }),
          h('button', { class: 'btn warn', type: 'button', text: 'মেলেনি', onclick: async () => { try { await act('review_confirm', { ...forDoctor(), id: r.id, ok: false, note: 'TrxID মেলেনি' }); toast('রোগীকে জানানো হয়েছে'); render(); } catch (e) { toast(e.message); } } }));
      }
      if (r.status === 'paid' && asDoctor) actions.append(h('button', { class: 'btn primary', type: 'button', text: 'রিপোর্ট দেখে জানান', onclick: () => readReport(page, r) }));
      return h('div', { class: 'card pad grid' },
        h('div', { class: 'toolbar', style: 'margin:0' }, h('b', { text: r.patientName || '—' }), h('span', { class: `chip ${r.status === 'claimed' || r.status === 'paid' ? 'pri' : r.status === 'done' ? 'good' : ''}`, text: RV[r.status] || r.status })),
        h('div', { class: 'note', text: `${r.labName} · ${(r.tests || []).join(', ')}` }),
        r.status === 'claimed' ? h('div', { text: `${PAY[r.method] || r.method} · TrxID: ${r.trxId}${r.fee ? ` · ${r.fee}` : ''}` }) : null,
        r.status === 'done' && r.comment ? h('div', { style: 'white-space:pre-wrap', text: r.comment }) : null,
        actions);
    }));
  }

  function reportNodes(o) {
    const rep = o.report || {};
    return [
      h('h3', { text: `${o.labName} · ${o.patientName}` }),
      (rep.items || []).length ? h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
        h('thead', null, h('tr', null, ['পরীক্ষা', 'ফল', 'স্বাভাবিক', ''].map((x) => h('th', { text: x })))),
        h('tbody', null, rep.items.map((i) => h('tr', null, h('td', { class: 'main-cell', text: i.test }), h('td', { text: `${i.result} ${i.unit || ''}` }), h('td', { text: i.range || '—' }),
          h('td', null, i.flag ? h('span', { class: 'chip bad', text: i.flag === 'high' ? 'বেশি' : 'কম' }) : null)))))) : null,
      rep.note ? h('p', { text: rep.note }) : null,
      (rep.photos || []).map((p) => h('img', { src: `data:image/jpeg;base64,${p}`, alt: 'রিপোর্টের ছবি', style: 'max-width:100%;border-radius:12px;margin-top:8px' })),
    ];
  }

  async function readReport(page, r) {
    page.replaceChildren(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    try {
      const o = (await act('lab_order_get', { id: r.labOrderId })).item;
      const comment = h('textarea', { class: 'reply', maxlength: 1000, placeholder: 'রোগীকে কী জানাবেন' });
      const send = h('button', { class: 'btn primary', type: 'button', text: 'রোগীকে পাঠান' });
      send.addEventListener('click', async () => {
        if (!comment.value.trim()) return;
        send.disabled = true;
        try { await act('review_done', { id: r.id, comment: comment.value.trim() }); toast('পাঠানো হয়েছে'); render(); } catch (e) { toast(e.message); send.disabled = false; }
      });
      page.replaceChildren(h('div', { class: 'toolbar' }, h('button', { class: 'btn quiet', type: 'button', text: '← ফিরে যান', onclick: render })),
        ...reportNodes(o), h('div', { class: 'block-title', text: 'আপনার মন্তব্য' }), comment,
        h('p', { class: 'note', text: 'ওষুধ বদলাতে হলে "নতুন প্রেসক্রিপশন" থেকে লিখুন।' }), h('div', { class: 'reply-actions' }, send));
    } catch (e) { page.replaceChildren(h('p', { class: 'error', text: e.message })); }
  }

  // ---------- ফার্মেসি: কোড দিয়ে খুলে ওষুধ দেওয়া, আসা প্রেসক্রিপশন ----------
  async function dispenseView(page) {
    const code = h('input', { class: 'cell-input', style: 'max-width:220px;text-transform:uppercase', maxlength: 10, placeholder: 'QR-এর নিচের কোড' });
    const out = h('div', { class: 'grid', style: 'margin-top:12px' });
    async function open(c) {
      out.replaceChildren(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
      try { drawRx((await act('rx_pharmacy_open', { code: c })).item); } catch (e) { out.replaceChildren(h('p', { class: 'error', text: e.message })); }
    }
    function drawRx(rx) {
      const qty = {};
      const rows = rx.medicines.map((m) => h('tr', null,
        h('td', { class: 'main-cell', text: `${formShort(m.form)} ${m.name}`.trim() }),
        h('td', { text: `${bn(m.dose)} · ${m.days ? `${bn(m.days)} দিন` : 'চলবে'}` }),
        h('td', { text: m.need != null ? `${bn(m.got)}/${bn(m.need)}` : m.got ? `${bn(m.got)}` : '—' }),
        h('td', null, m.done || rx.status === 'cancelled' ? h('span', { class: 'chip good', text: 'দেওয়া শেষ' })
          : h('input', { class: 'cell-input', style: 'max-width:90px', inputmode: 'numeric', placeholder: m.left != null ? `বাকি ${bn(m.left)}` : 'কয়টা', oninput: (e) => { qty[m.i] = Number(e.target.value.replace(/\D/g, '')) || 0; } }))));
      const save = h('button', { class: 'btn primary', type: 'button', text: 'যা দিলাম লিখে রাখুন' });
      save.addEventListener('click', async () => {
        const items = Object.entries(qty).filter(([, q]) => q > 0).map(([i, q]) => ({ i: Number(i), qty: q }));
        if (!items.length) return;
        save.disabled = true;
        try { drawRx((await act('rx_dispense', { code: rx.code, items })).item); toast('লেখা হয়েছে'); } catch (e) { toast(e.message); save.disabled = false; }
      });
      out.replaceChildren(h('div', { class: 'card pad grid' },
        rx.status === 'cancelled' ? h('p', { class: 'error', text: 'বাতিল করা প্রেসক্রিপশন — ওষুধ দেবেন না' }) : null,
        h('b', { text: `${rx.doctor.name}${rx.doctor.regNo ? ` · BMDC ${rx.doctor.regNo}` : ''}` }),
        h('div', { class: 'note', text: `${rx.patient.name}${rx.patient.age != null ? `, ${bn(rx.patient.age)} বছর` : ''} · ${fmtDate(rx.createdAt)}` }),
        h('div', { class: 'table-wrap' }, h('table', { class: 't' }, h('thead', null, h('tr', null, ['ওষুধ', 'নিয়ম', 'কেনা', 'এখন দিলেন'].map((x) => h('th', { text: x })))), h('tbody', null, rows))),
        (rx.dispensed || []).length ? h('div', { class: 'note', text: 'আগে দেওয়া: ' + rx.dispensed.map((d) => `${d.name} (${fmtDate(d.at)})`).join(', ') }) : null,
        rx.status !== 'cancelled' && rx.medicines.some((m) => !m.done) ? h('div', null, save) : null));
    }
    const incoming = h('div', { class: 'grid' });
    page.append(h('div', { class: 'card pad grid' }, h('b', { text: 'প্রেসক্রিপশনের ওষুধ দিন' }),
      h('p', { class: 'note', text: 'কোড দিয়ে খুলে কয়টা দিলেন লিখুন। বাকিটা রোগী অন্য ফার্মেসি থেকে নিতে পারবেন।' }),
      h('div', { class: 'field-row' }, code, h('button', { class: 'btn primary', type: 'button', text: 'খুলুন', onclick: () => open(code.value.trim().toUpperCase()) }))), out,
      h('div', { class: 'block-title', text: 'আপনার কাছে পাঠানো প্রেসক্রিপশন' }), incoming);
    try {
      const items = (await act('rx_orders_pharmacy')).items.filter((o) => ['sent', 'ready'].includes(o.status));
      incoming.replaceChildren(...(items.length ? items.map((o) => h('div', { class: 'ctl' },
        h('div', { class: 'what' }, h('b', { text: o.patientName || '—' }), h('span', { class: 'note', text: `${o.status === 'ready' ? 'তৈরি' : 'নতুন'} · ${fmtDate(o.createdAt)}` })),
        h('div', { class: 'actions' },
          h('button', { class: 'btn primary', type: 'button', text: 'খুলুন', onclick: () => { code.value = o.code; open(o.code); } }),
          o.status === 'sent' ? h('button', { class: 'btn quiet', type: 'button', text: 'তৈরি', onclick: async () => { try { await act('rx_order_update', { id: o.id, status: 'ready' }); toast('রোগীকে জানানো হয়েছে'); render(); } catch (e) { toast(e.message); } } }) : null,
          h('button', { class: 'btn warn', type: 'button', text: 'পারব না', onclick: async () => { try { await act('rx_order_update', { id: o.id, status: 'cancelled', reason: 'ওষুধ নেই' }); toast('রোগীকে জানানো হয়েছে'); render(); } catch (e) { toast(e.message); } } })))) : [h('p', { class: 'note', text: 'এখন কিছু নেই' })]));
    } catch (e) { incoming.replaceChildren(h('p', { class: 'error', text: e.message })); }
  }

  // ---------- ফার্মেসি: স্টক ----------
  async function stockView(page) {
    let names = [];
    try { names = (await act('stock_get')).items; } catch (e) { page.append(h('p', { class: 'error', text: e.message })); return; }
    const list = h('div', { class: 'chips' });
    const countEl = h('span', { class: 'count' });
    let q = '';
    function draw() {
      const shown = names.filter((n) => !q || n.toLowerCase().includes(q));
      countEl.textContent = `${bn(names.length)}টা ওষুধ`;
      list.replaceChildren(...shown.slice(0, 500).map((n) => h('span', { class: 'chip' }, n, ' ', h('button', { class: 'link', type: 'button', title: 'সরান', text: '✕', onclick: () => change([], [n]) }))));
    }
    async function change(add, remove) {
      try { names = (await act('stock_set', { add, remove })).items; draw(); } catch (e) { toast(e.message); }
    }
    const dl = h('datalist', { id: 'stock-drugs' });
    let t = null;
    const input = h('input', { list: 'stock-drugs', placeholder: 'ওষুধের নাম লিখে বাছুন', oninput: (e) => {
      clearTimeout(t);
      const v = e.target.value.trim();
      if (v.length < 2) return;
      t = setTimeout(async () => { try { const r = await act('drug_search', { q: v }); dl.replaceChildren(...(r.items || []).map((d) => h('option', { value: d.name, text: [d.generic, d.form].filter(Boolean).join(' · ') }))); } catch (err) { /* লেখা চলবে */ } }, 300);
    } });
    const bulk = h('textarea', { class: 'reply', placeholder: 'অনেকগুলো একসাথে: প্রতি লাইনে একটা নাম' });
    page.append(h('div', { class: 'card pad grid' },
      h('b', { text: 'স্টকের তালিকা' }),
      h('p', { class: 'note', text: 'রোগীরা প্রেসক্রিপশন থেকে দেখতে পাবেন কোন ফার্মেসিতে কয়টা ওষুধ আছে।' }),
      h('div', { class: 'field-row' }, input, dl, h('button', { class: 'btn primary', type: 'button', text: 'যোগ', onclick: () => { const v = input.value.trim(); if (v) { input.value = ''; change([v], []); } } })),
      bulk, h('div', null, h('button', { class: 'btn tonal', type: 'button', text: 'সব যোগ করুন', onclick: () => { const v = bulk.value.split('\n').map((x) => x.trim()).filter(Boolean); if (v.length) { bulk.value = ''; change(v, []); } } })),
      h('div', { class: 'toolbar', style: 'margin:0' }, h('div', { class: 'search' }, icon('search'), h('input', { placeholder: 'তালিকায় খুঁজুন', oninput: (e) => { q = e.target.value.trim().toLowerCase(); draw(); } })), countEl),
      list));
    draw();
  }

  // ---------- প্যাথলজি: পরীক্ষা আর রিপোর্ট ----------
  const LS = { sent: 'নতুন', received: 'নমুনা নেওয়া হয়েছে', reported: 'রিপোর্ট দেওয়া হয়েছে', cancelled: 'বাতিল' };

  /** ছবি ছোট করে JPEG base64 (≤ ~২৮০ KB) */
  function shrink(file) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      const url = URL.createObjectURL(file);
      img.onload = () => {
        let max = 1400;
        let q = 0.75;
        for (let i = 0; i < 8; i++) {
          const sc = Math.min(1, max / Math.max(img.width, img.height));
          const c = document.createElement('canvas');
          c.width = Math.round(img.width * sc);
          c.height = Math.round(img.height * sc);
          c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
          const b64 = c.toDataURL('image/jpeg', q).split(',')[1];
          if (b64.length <= 280 * 1024 || max <= 700) { URL.revokeObjectURL(url); resolve(b64); return; }
          if (q > 0.55) q -= 0.1; else max -= 200;
        }
        reject(new Error('ছবি অনেক বড়'));
      };
      img.onerror = () => reject(new Error('ছবিটা পড়া যায়নি'));
      img.src = url;
    });
  }

  async function testsView(page) {
    const code = h('input', { class: 'cell-input', style: 'max-width:220px;text-transform:uppercase', maxlength: 10, placeholder: 'QR-এর নিচের কোড' });
    const listBox = h('div', { class: 'grid' }, h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    page.append(h('div', { class: 'card pad grid' }, h('b', { text: 'কাগজের প্রেসক্রিপশন নিয়ে এলে' }),
      h('div', { class: 'field-row' }, code, h('button', { class: 'btn primary', type: 'button', text: 'খুলুন', onclick: async () => {
        try { const o = (await act('lab_open_code', { code: code.value.trim().toUpperCase() })).item; reportForm(page, o); } catch (e) { toast(e.message); }
      } }))), h('div', { class: 'block-title', text: 'পরীক্ষার অনুরোধ' }), listBox);
    try {
      const items = (await act('lab_orders')).items;
      listBox.replaceChildren(...(items.length ? items.map((o) => h('div', { class: 'ctl' },
        h('div', { class: 'what' }, h('b', { text: o.patientName || '—' }), h('span', { class: 'note', text: `${o.tests.join(', ')} · ${LS[o.status] || o.status} · ${fmtDate(o.createdAt)}` })),
        h('div', { class: 'actions' },
          o.status === 'sent' ? h('button', { class: 'btn quiet', type: 'button', text: 'নমুনা নিয়েছি', onclick: async () => { try { await act('lab_order_status', { id: o.id, status: 'received' }); render(); } catch (e) { toast(e.message); } } }) : null,
          ['sent', 'received'].includes(o.status) ? h('button', { class: 'btn primary', type: 'button', text: 'রিপোর্ট দিন', onclick: () => reportForm(page, o) }) : null,
          o.status === 'reported' ? h('button', { class: 'btn quiet', type: 'button', text: 'দেখুন', onclick: async () => {
            try { const full = (await act('lab_order_get', { id: o.id })).item; page.replaceChildren(h('div', { class: 'toolbar' }, h('button', { class: 'btn quiet', type: 'button', text: '← ফিরে যান', onclick: render })), ...reportNodes(full)); } catch (e) { toast(e.message); }
          } }) : null))) : [h('p', { class: 'note', text: 'এখনো কোনো অনুরোধ নেই' })]));
    } catch (e) { listBox.replaceChildren(h('p', { class: 'error', text: e.message })); }
  }

  function reportForm(page, o) {
    const rows = o.tests.map((t) => ({ test: t, result: '', unit: '', range: '', flag: '' }));
    const body = h('tbody');
    function draw() {
      body.replaceChildren(...rows.map((r) => h('tr', null,
        h('td', null, h('input', { value: r.test, oninput: (e) => { r.test = e.target.value; } })),
        h('td', null, h('input', { value: r.result, oninput: (e) => { r.result = e.target.value; } })),
        h('td', null, h('input', { value: r.unit, oninput: (e) => { r.unit = e.target.value; } })),
        h('td', null, h('input', { value: r.range, oninput: (e) => { r.range = e.target.value; } })),
        h('td', null, h('select', { onchange: (e) => { r.flag = e.target.value; } }, [['', '—'], ['high', 'বেশি'], ['low', 'কম']].map(([v, l]) => h('option', { value: v, selected: r.flag === v, text: l })))))));
    }
    draw();
    const note = h('textarea', { class: 'reply', maxlength: 500, placeholder: 'নোট (ঐচ্ছিক)' });
    const photos = [];
    const photoInfo = h('span', { class: 'note' });
    const file = h('input', { type: 'file', accept: 'image/*', onchange: async (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (!f || photos.length >= 2) return;
      try { photos.push(await shrink(f)); photoInfo.textContent = `${bn(photos.length)}টা ছবি`; } catch (err) { toast(err.message); }
    } });
    const send = h('button', { class: 'btn primary', type: 'button', text: 'রিপোর্ট পাঠান' });
    send.addEventListener('click', async () => {
      send.disabled = true;
      try {
        await act('lab_report', { id: o.id, items: rows.filter((r) => r.test.trim() && r.result.trim()), note: note.value.trim(), photos });
        toast('রোগীর কাছে রিপোর্ট গেছে');
        render();
      } catch (e) { toast(e.message); send.disabled = false; }
    });
    page.replaceChildren(h('div', { class: 'toolbar' }, h('button', { class: 'btn quiet', type: 'button', text: '← ফিরে যান', onclick: render })),
      h('div', { class: 'card pad grid' },
        h('b', { text: [`${o.patientName}${o.patientAge != null ? `, ${bn(o.patientAge)} বছর` : ''}`, o.doctorName].filter(Boolean).join(' · ') }),
        h('div', { class: 'table-wrap' }, h('table', { class: 'meds' }, h('thead', null, h('tr', null, ['পরীক্ষা', 'ফল', 'একক', 'স্বাভাবিক মাত্রা', 'বেশি/কম'].map((x) => h('th', { text: x })))), body)),
        h('div', null, h('button', { class: 'btn tonal', type: 'button', text: 'আরেকটা সারি', onclick: () => { rows.push({ test: '', result: '', unit: '', range: '', flag: '' }); draw(); } })),
        note,
        h('div', { class: 'grid', style: 'gap:6px' }, h('span', { class: 'note', text: 'ছাপানো রিপোর্টের ছবি (ঐচ্ছিক, সর্বোচ্চ ২টা)' }), h('div', { class: 'field-row', style: 'align-items:center' }, file, photoInfo)),
        h('div', null, send)));
  }

  start();
})();
