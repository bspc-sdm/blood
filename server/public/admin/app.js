// Lifolo ওয়েব অ্যাডমিন প্যানেল — বাম মেনু, ডানে পুরো পাতা।
// নিরাপত্তা: ব্যবহারকারীর লেখা সবসময় textContent দিয়ে বসে (কখনো innerHTML নয়);
// লগইন httpOnly কুকিতে, এই স্ক্রিপ্ট সেটা দেখতে পায় না।
(function () {
  'use strict';

  const IDLE_MS = 2 * 3600 * 1000; // ২ ঘণ্টা কিছু না করলে লগআউট (সার্ভারেও একই)
  const WARN_MS = 2 * 60 * 1000; // তার ২ মিনিট আগে সতর্ক করা
  const AUTO_REFRESH_MS = 5 * 60 * 1000;
  const root = document.getElementById('root');
  const toastEl = document.getElementById('toast');
  const ICONS = window.LF_ICONS || {};

  const S = {
    me: null,
    overview: null,
    overviewAt: 0,
    stats: null,
    lists: {}, // "pharmacies:approved" → items
    loggedIn: false,
    busy: 0,
    lastActive: Date.now(),
  };

  // =========================================================
  // ছোট সাহায্যকারী
  // =========================================================
  const BN = '০১২৩৪৫৬৭৮৯';
  const bn = (v) => String(v ?? '').replace(/[0-9]/g, (d) => BN[d]);
  const SVGNS = 'http://www.w3.org/2000/svg';

  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    setProps(el, props);
    append(el, kids);
    return el;
  }
  function s(tag, props, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    setProps(el, props);
    append(el, kids);
    return el;
  }
  function setProps(el, props) {
    if (!props) return;
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.setAttribute('class', v);
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  function append(el, kids) {
    for (const kid of kids.flat(3)) {
      if (kid == null || kid === false || kid === '') continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
  }
  function icon(name, cls) {
    return s('svg', { class: cls || 'ic', viewBox: '0 0 960 960', 'aria-hidden': 'true' }, s('path', { d: ICONS[name] || '' }));
  }

  const tz = { timeZone: 'Asia/Dhaka' };
  const dFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short', year: 'numeric' });
  const dtFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
  const dayFmt = new Intl.DateTimeFormat('bn-BD', { ...tz, day: 'numeric', month: 'short' });
  const valid = (ms) => typeof ms === 'number' && ms > 0 && !Number.isNaN(new Date(ms).getTime());
  const fmtDate = (ms) => (valid(ms) ? dFmt.format(new Date(ms)) : '—');
  const fmtDT = (ms) => (valid(ms) ? dtFmt.format(new Date(ms)) : '—');
  function ago(ms) {
    if (!valid(ms)) return '—';
    const m = Math.floor((Date.now() - ms) / 60000);
    if (m < 1) return 'এইমাত্র';
    if (m < 60) return `${bn(m)} মিনিট আগে`;
    const hr = Math.floor(m / 60);
    if (hr < 24) return `${bn(hr)} ঘণ্টা আগে`;
    const d = Math.floor(hr / 24);
    return d < 30 ? `${bn(d)} দিন আগে` : fmtDate(ms);
  }
  const num = (n) => (n == null ? '—' : bn(Number(n).toLocaleString('en-IN')));

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    clearTimeout(toast.t);
    toast.t = setTimeout(() => { toastEl.hidden = true; }, 3200);
  }
  function store(key, value) {
    try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) { /* না থাকলেও চলে */ }
  }
  function load(key) {
    try { return localStorage.getItem(key) || ''; } catch (e) { return ''; }
  }

  // =========================================================
  // লেবেল (অ্যাপের সাথে মিলিয়ে)
  // =========================================================
  const L = {
    badge: { doctor: 'ডাক্তার', nurse: 'নার্স', nutritionist: 'পুষ্টিবিদ', trainer: 'হেলথ ট্রেইনার' },
    hospitalType: {
      govt_mch: 'সরকারি মেডিকেল কলেজ হাসপাতাল', govt_specialized: 'সরকারি বিশেষায়িত হাসপাতাল',
      govt_district: 'জেলা / সদর হাসপাতাল', govt_uhc: 'উপজেলা স্বাস্থ্য কমপ্লেক্স', govt_hospital: 'অন্যান্য সরকারি হাসপাতাল',
      private_hospital: 'বেসরকারি হাসপাতাল', clinic: 'ক্লিনিক', diagnostic: 'ডায়াগনস্টিক সেন্টার',
    },
    amb: { general: 'সাধারণ', ac: 'এসি', icu: 'আইসিইউ', freezer: 'ফ্রিজার ভ্যান' },
    report: { question: 'প্রশ্ন', answer: 'উত্তর', tip: 'টিপস', ambulance: 'অ্যাম্বুলেন্স', pharmacy: 'ফার্মেসি', homecare: 'বাসায় সেবা', provider: 'ডাক্তার/নার্স (চেম্বার)', bloodbank: 'ব্লাড ব্যাংক' },
    bankKind: { govt: 'সরকারি হাসপাতাল', private: 'বেসরকারি', org: 'স্বেচ্ছাসেবী সংগঠন' },
    bankService: { whole_blood: 'পূর্ণ রক্ত', components: 'প্লাজমা ও অন্যান্য অংশ', platelets: 'প্লাটিলেট', screening: 'স্ক্রিনিং ও ক্রস-ম্যাচ', donate_here: 'এখানে রক্ত দেওয়া যায়' },
    listing: { pending: 'অনুমোদনের অপেক্ষায়', approved: 'অনুমোদিত', suspended: 'বন্ধ', rejected: 'বাতিল' },
    order: { open: 'ফার্মেসির উত্তরের অপেক্ষা', confirmed: 'নিশ্চিত', ready: 'তৈরি', on_the_way: 'রওনা দিয়েছে', done: 'সম্পন্ন', expired: 'মেয়াদ শেষ', no_show: 'নেওয়া হয়নি', cancelled: 'বাতিল' },
    visit: { open: 'নার্স খোঁজা চলছে', accepted: 'নার্স রাজি', on_the_way: 'নার্স রওনা দিয়েছেন', done: 'সম্পন্ন', expired: 'মেয়াদ শেষ', cancelled: 'বাতিল' },
    appt: { pending: 'অপেক্ষায়', confirmed: 'নিশ্চিত', declined: 'ফিরিয়ে দেওয়া', cancelled: 'বাতিল', expired: 'মেয়াদ শেষ', completed: 'সম্পন্ন' },
    request: { open: 'চালু', closed: 'বন্ধ' },
    ticket: { open: 'উত্তরের অপেক্ষায়', answered: 'উত্তর দেওয়া হয়েছে', closed: 'বন্ধ' },
    category: {
      account: 'অ্যাকাউন্ট', badge: 'ব্যাজ (ডাক্তার/নার্স)', pharmacy: 'ফার্মেসি', ambulance: 'অ্যাম্বুলেন্স', bloodbank: 'ব্লাড ব্যাংক', homevisit: 'হোম ভিজিট (আগের)', homecare: 'বাসায় সেবা',
      medorder: 'ওষুধের অর্ডার', appointment: 'অ্যাপয়েন্টমেন্ট', blood: 'রক্তের অনুরোধ', other: 'অন্য কিছু',
    },
    log: {
      admin_badge_resolve: 'ব্যাজ', admin_pharmacy_resolve: 'ফার্মেসি', admin_ambulance_resolve: 'অ্যাম্বুলেন্স', admin_bloodbank_resolve: 'ব্লাড ব্যাংক', admin_bloodbank_add: 'ব্লাড ব্যাংক যোগ', admin_bloodbank_edit: 'ব্লাড ব্যাংক সম্পাদনা', admin_bloodbank_delete: 'ব্লাড ব্যাংক মোছা',
      admin_hospital_resolve: 'হাসপাতাল', admin_report_resolve: 'রিপোর্ট', admin_delete_post: 'পোস্ট মোছা (অ্যাপ)', admin_user_section: 'ব্যবহারকারীর তথ্য দেখা', admin_verify_docs: 'কাগজের ছবি দেখা', admin_request_docs: 'কাগজের ছবি চাওয়া', admin_ticket_reply: 'আপিল',
      admin_emergency_create: 'জরুরি আহ্বান', admin_emergency_more: 'জরুরি: আরও জানানো', admin_emergency_group: 'জরুরি: গ্রুপ', admin_emergency_close: 'জরুরি: শেষ',
      admin_user_action: 'ব্যবহারকারী', admin_add: 'অ্যাডমিন যোগ', admin_remove: 'অ্যাডমিন বাদ', correction_resolve: 'তারিখ সংশোধন',
    },
    detail: {
      approved: 'অনুমোদন', rejected: 'বাতিল', suspended: 'বন্ধ', approve: 'অনুমোদন', reject: 'বাতিল', removed: 'সরানো হয়েছে', kept: 'রেখে দেওয়া',
      replied: 'উত্তর', 'replied+closed': 'উত্তর দিয়ে বন্ধ', closed: 'বন্ধ', homevisit_unban: 'বাসায় সেবার নিষেধাজ্ঞা তোলা',
      homevisit_ban_patient: 'হোম ভিজিট বন্ধ (রোগী)', homevisit_ban_nurse: 'হোম ভিজিট বন্ধ (নার্স)', homecare_ban: 'বাসায় সেবা বন্ধ', provider_unsuspend: 'ডাক্তারের তালিকা আবার চালু', provider_suspend: 'ডাক্তারের তালিকা বন্ধ', med_unblock: 'ওষুধের অর্ডারের ব্লক তোলা',
      disable: 'অ্যাকাউন্ট বন্ধ', enable: 'অ্যাকাউন্ট খোলা',
    },
  };
  const listingChip = (st) => h('span', { class: `chip ${st === 'approved' ? 'good' : st === 'suspended' || st === 'rejected' ? 'bad' : 'pri'}`, text: L.listing[st] || st || '' });

  // =========================================================
  // সার্ভার
  // =========================================================
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
      if (S.loggedIn && (res.status === 401 || json.code === 'session_expired')) showLogin(err.message);
      throw err;
    }
    return json;
  }
  // panel: true — সার্ভার শুধু প্যানেলের জন্য বাড়তি জিনিস দেয় (ব্যাজ সংশোধন, চেম্বারের রিপোর্ট)
  const act = (action, body) => post('/api/community', { action, panel: true, ...body });

  async function loadOverview() {
    S.overview = await act('admin_overview');
    S.overviewAt = Date.now();
    S.me = S.overview.me;
    // অপেক্ষার তালিকা ক্যাশে
    S.lists['badges:pending'] = itemsOf('badges');
    S.lists['pharmacies:pending'] = itemsOf('pharmacies');
    S.lists['ambulances:pending'] = itemsOf('ambulances');
    S.lists['bloodBanks:pending'] = itemsOf('bloodBanks');
    S.lists['tickets:open'] = itemsOf('tickets');
  }
  const itemsOf = (key) => (S.overview && S.overview[key] && Array.isArray(S.overview[key].items) ? S.overview[key].items : []);
  const pendingCount = (key) => itemsOf(key).length;

  // =========================================================
  // লগইন
  // =========================================================
  function showLogin(message) {
    S.loggedIn = false;
    S.overview = null;
    S.lists = {};
    S.stats = null;
    let stage = 'email';
    let email = load('lf_admin_email');
    let error = message || '';
    let sending = false;
    let info = '';
    // আবার কোড চাওয়া যায় ১ মিনিট পর (সার্ভারের সীমার সাথে মিলিয়ে)
    let resendAt = 0;
    let tick = null;
    const waitSec = () => Math.max(0, Math.ceil((resendAt - Date.now()) / 1000));
    function startTimer() {
      resendAt = Date.now() + 60 * 1000;
      clearInterval(tick);
      tick = setInterval(() => {
        const b = root.querySelector('.resend');
        if (!b) { clearInterval(tick); return; }
        const n = waitSec();
        b.disabled = n > 0 || sending;
        b.textContent = n > 0 ? `আবার কোড পাঠান (${bn(n)} সেকেন্ড)` : 'আবার কোড পাঠান';
        if (!n) clearInterval(tick);
      }, 1000);
    }
    async function resend() {
      if (sending || waitSec() > 0) return;
      sending = true; error = ''; info = ''; draw();
      try {
        await post('/api/auth/send-code', { email });
        info = 'নতুন কোড গেছে। না পেলে Spam বা All Mail দেখুন।';
        startTimer();
      } catch (e) { error = e.message; }
      sending = false; draw();
    }

    function draw() {
      const form = h('form', { class: 'card login', novalidate: true, onsubmit: submit },
        h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), h('span', { text: 'অ্যাডমিন' })),
        h('h1', { text: 'লগইন' }),
        stage === 'code' ? h('p', { text: `কোড গেছে: ${email}` }) : null,
        error ? h('p', { class: 'error', role: 'alert', text: error }) : null,
        info && stage === 'code' ? h('p', { class: 'note', role: 'status', text: info }) : null,
        stage === 'email'
          ? [h('label', { class: 'field' }, h('span', { text: 'ইমেইল' }),
            h('input', { type: 'email', name: 'email', autocomplete: 'email', required: true, value: email })),
          h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'পাঠানো হচ্ছে…' : 'কোড পাঠান' }))]
          : [h('label', { class: 'field' }, h('span', { text: 'কোড' }),
            h('input', { class: 'code-input', name: 'code', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, required: true })),
          h('div', { class: 'row' },
            h('button', { class: 'btn primary', type: 'submit', disabled: sending, text: sending ? 'যাচাই হচ্ছে…' : 'লগইন করুন' }),
            h('button', { class: 'link', type: 'button', onclick: () => { stage = 'email'; error = ''; info = ''; draw(); }, text: 'ইমেইল বদলান' })),
          h('div', { class: 'row' },
            h('button', { class: 'link resend', type: 'button', disabled: sending || waitSec() > 0, onclick: resend,
              text: waitSec() > 0 ? `আবার কোড পাঠান (${bn(waitSec())} সেকেন্ড)` : 'আবার কোড পাঠান' }))],
        null);
      root.replaceChildren(h('div', { class: 'login-wrap' }, form));
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
          startTimer();
        } catch (e) { error = e.message; }
        sending = false; draw();
      } else {
        const code = input.value.replace(/[০-৯]/g, (d) => String(BN.indexOf(d))).replace(/\D/g, '');
        if (code.length !== 6) { error = '৬ সংখ্যার কোড দিন'; draw(); return; }
        sending = true; draw();
        try {
          await post('/api/auth/verify-code', { email, code, web: true });
          S.lastActive = Date.now();
          await start();
          return;
        } catch (e) { error = e.message; }
        sending = false; draw();
      }
    }
    draw();
  }

  async function logout(message) {
    try { await act('web_logout'); } catch (e) { /* মেয়াদ শেষ হলেও লগইন পাতা */ }
    showLogin(message);
  }

  async function start() {
    try {
      await loadOverview();
    } catch (e) {
      if (e.status === 401 || e.status === 403 || e.code === 'session_expired') {
        showLogin(e.status === 403 ? e.message : '');
      } else {
        root.replaceChildren(h('div', { class: 'login-wrap' }, h('div', { class: 'card login' },
          h('p', { class: 'error', role: 'alert', text: e.message }),
          h('button', { class: 'btn quiet', type: 'button', onclick: start, text: 'আবার চেষ্টা করুন' }))));
      }
      return;
    }
    S.loggedIn = true;
    buildShell();
    route();
  }

  // =========================================================
  // কাঠামো: বাম মেনু + উপরের সারি + পাতা
  // =========================================================
  const NAV = [
    { group: null, items: [
      { path: '', label: 'ড্যাশবোর্ড', icon: 'home' },
      { path: 'analytics', label: 'অ্যানালিটিক্স', icon: 'chart' },
      { path: 'emergency', label: 'জরুরি আহ্বান', icon: 'warning' },
    ] },
    { group: 'অনুমোদন', items: [
      { path: 'badges', label: 'ব্যাজ', icon: 'verified', count: () => pendingCount('badges') },
      { path: 'pharmacies', label: 'ফার্মেসি', icon: 'store', count: () => pendingCount('pharmacies') },
      { path: 'ambulances', label: 'অ্যাম্বুলেন্স', icon: 'ambulance', count: () => pendingCount('ambulances') },
      { path: 'bloodBanks', label: 'ব্লাড ব্যাংক', icon: 'blood', count: () => pendingCount('bloodBanks') },
      { path: 'hospitals', label: 'হাসপাতাল', icon: 'hospital', count: () => pendingCount('hospitalRequests') },
    ] },
    { group: 'সহায়তা', items: [
      { path: 'tickets', label: 'আপিল ও সমস্যা', icon: 'help', count: () => pendingCount('tickets') },
      { path: 'reports', label: 'রিপোর্ট', icon: 'flag', count: () => pendingCount('reports') },
      { path: 'corrections', label: 'তারিখ সংশোধন', icon: 'calendar', count: () => pendingCount('corrections') },
    ] },
    { group: 'ব্যবস্থাপনা', items: [
      { path: 'users', label: 'ব্যবহারকারী', icon: 'person' },
      { path: 'log', label: 'কাজের ইতিহাস', icon: 'history' },
      { path: 'admins', label: 'অ্যাডমিন', icon: 'admin', owner: true },
    ] },
  ];

  let shell = null;
  let navEl = null;
  let titleEl = null;
  let pageEl = null;
  let topActions = null;

  function buildShell() {
    navEl = h('nav', { class: 'nav', 'aria-label': 'মেনু' });
    titleEl = h('h1');
    topActions = h('div', { class: 'top-actions' });
    pageEl = h('main', { class: 'page', id: 'page', tabindex: '-1' });
    shell = h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), h('span', { text: 'অ্যাডমিন' })),
        navEl,
        h('div', { class: 'side-foot' },
          h('div', { class: 'who', text: (S.me && S.me.email) || '' }),
          h('button', { class: 'btn quiet', type: 'button', onclick: () => logout('লগআউট হয়েছে।') }, icon('logout'), 'লগআউট'))),
      h('div', { class: 'scrim', onclick: () => shell.classList.remove('open') }),
      h('div', { class: 'main' },
        h('header', { class: 'topbar' },
          h('button', { class: 'icon-btn menu-btn', type: 'button', 'aria-label': 'মেনু খুলুন', onclick: () => shell.classList.add('open') }, icon('menu')),
          titleEl,
          topActions,
          h('button', { class: 'icon-btn', type: 'button', title: 'রিফ্রেশ', 'aria-label': 'রিফ্রেশ', onclick: () => refreshAll() }, icon('refresh'))),
        pageEl));
    root.replaceChildren(shell);
    drawNav();
  }

  function drawNav() {
    if (!navEl) return;
    const cur = parseHash().name;
    const kids = [];
    for (const g of NAV) {
      if (g.group) kids.push(h('div', { class: 'nav-group', text: g.group }));
      for (const it of g.items) {
        if (it.owner && !(S.me && S.me.isOwner)) continue;
        const n = it.count ? it.count() : 0;
        const on = cur === it.path || (cur === 'user' && it.path === 'users') || (cur === 'ticket' && it.path === 'tickets')
          || (cur === 'verify' && parseHash().arg.startsWith(`${it.path}:`));
        kids.push(h('a', { href: `#/${it.path}`, class: on ? 'on' : null, 'aria-current': on ? 'page' : null, onclick: (ev) => {
          shell.classList.remove('open');
          // এখনকার পাতার মেনুতেই চাপলে ঠিকানা বদলায় না — তবু নতুন করে আনা
          if (location.hash === `#/${it.path}`) { ev.preventDefault(); route(false, true); }
        } },
          icon(it.icon), h('span', { class: 'lbl', text: it.label }), n ? h('span', { class: 'n', text: bn(n) }) : null));
      }
    }
    navEl.replaceChildren(...kids);
  }

  function setTitle(title, sub, actions) {
    titleEl.replaceChildren(...[title, sub ? h('span', { class: 'sub', text: sub }) : null].filter(Boolean));
    topActions.replaceChildren(...(actions || []));
    document.title = `${title} — Lifolo অ্যাডমিন`;
  }

  async function refreshAll() {
    try {
      await loadOverview();
      // অন্য অবস্থার তালিকা আবার আনা হবে
      for (const k of Object.keys(S.lists)) if (!/:(pending|open)$/.test(k)) delete S.lists[k];
      drawNav();
      route(true);
      toast('হালনাগাদ হয়েছে');
    } catch (e) {
      if (S.loggedIn) toast(e.message);
    }
  }

  // =========================================================
  // রাউটিং (#/pharmacies?s=approved)
  // =========================================================
  function parseHash() {
    const raw = location.hash.replace(/^#\/?/, '');
    const [path, query] = raw.split('?');
    const parts = path.split('/');
    const params = new URLSearchParams(query || '');
    return { name: parts[0] || '', arg: parts[1] ? decodeURIComponent(parts[1]) : '', params };
  }
  const go = (hash) => { location.hash = hash; };

  const PAGES = {
    '': pageDashboard,
    analytics: pageAnalytics,
    emergency: pageEmergency,
    badges: (r, alive) => pageListing('badges', r, alive),
    pharmacies: (r, alive) => pageListing('pharmacies', r, alive),
    ambulances: (r, alive) => pageListing('ambulances', r, alive),
    bloodBanks: (r, alive) => pageListing('bloodBanks', r, alive),
    hospitals: pageHospitals,
    tickets: pageTickets,
    ticket: pageTicket,
    reports: pageReports,
    corrections: pageCorrections,
    users: pageUsers,
    user: pageUser,
    log: pageLog,
    admins: pageAdmins,
    verify: pageVerify,
  };

  let routeSeq = 0;
  /**
   * পাতা আঁকা। fresh = মেনু থেকে আসা: সার্ভার থেকে নতুন তথ্য এনে তারপর আঁকা
   * (আগে লগইনের সময়ের তথ্যই দেখাত, নতুন রিপোর্ট/আবেদন দেখতে ব্রাউজার রিলোড লাগত)
   */
  function route(keepScroll, fresh) {
    if (!S.loggedIn || !pageEl) return;
    const r = parseHash();
    const fn = PAGES[r.name] || pageDashboard;
    const seq = ++routeSeq;
    const alive = () => seq === routeSeq;
    drawNav();
    const y = window.scrollY;
    pageEl.replaceChildren(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    const load = async () => {
      if (fresh) {
        S.lists = {};
        S.stats = null;
        await loadOverview();
        if (!alive()) return;
        drawNav();
      }
      return fn(r, alive);
    };
    load().catch((e) => {
      if (seq !== routeSeq) return;
      pageEl.replaceChildren(h('p', { class: 'error', role: 'alert', text: e.message }),
        h('button', { class: 'btn quiet', type: 'button', onclick: () => route(false, true), text: 'আবার চেষ্টা করুন' }));
    }).then(() => {
      if (keepScroll) window.scrollTo(0, y);
    });
    if (!keepScroll) window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', () => route(false, true));

  /** পাতা আঁকা — পুরোনো রাউটের দেরিতে আসা উত্তর যাতে নতুন পাতা না ঢেকে দেয় */
  function paint(alive, ...nodes) {
    if (alive && !alive()) return;
    pageEl.replaceChildren(...nodes.flat().filter(Boolean));
  }

  // =========================================================
  // কাজের বাটন: warn হলে প্রথম চাপে "নিশ্চিত?"
  // =========================================================
  function actionBtn(label, kind, run, okMsg, after) {
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
      const scope = b.closest('tr, .ctl, .card') || b.parentElement;
      const buttons = scope.querySelectorAll('button');
      buttons.forEach((x) => { x.disabled = true; });
      b.textContent = '…';
      S.busy++;
      try {
        await run();
        if (okMsg) toast(okMsg);
        if (after) await after();
      } catch (e) {
        buttons.forEach((x) => { x.disabled = false; });
        armed = false;
        b.classList.remove('armed');
        b.textContent = label;
        const old = scope.querySelector('.row-error');
        if (old) old.remove();
        const holder = b.closest('td') || scope;
        if (S.loggedIn) holder.append(h('div', { class: 'row-error', role: 'alert', text: e.message }));
      } finally {
        S.busy--;
      }
    });
    return b;
  }

  /** কাজ শেষে: অপেক্ষার সংখ্যা হালনাগাদ, এই তালিকা আবার আঁকা */
  async function afterChange() {
    try { await loadOverview(); } catch (e) { /* পরে আবার হবে */ }
    for (const k of Object.keys(S.lists)) if (!/:(pending|open)$/.test(k)) delete S.lists[k];
    drawNav();
    route(true);
  }

  // =========================================================
  // টেবিল
  // =========================================================
  /** cols: [{label, cell(item) → node|string, main?}], actions(item) → [buttons] */
  function table(items, cols, actions, onRow) {
    if (!items.length) return h('div', { class: 'card empty', text: 'এখানে কিছু নেই।' });
    return h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
      h('thead', null, h('tr', null, cols.map((c) => h('th', { scope: 'col', text: c.label })), actions ? h('th', null) : null)),
      h('tbody', null, items.map((it) => h('tr', { class: onRow ? 'click' : null, onclick: onRow ? (ev) => { if (!ev.target.closest('button, a')) onRow(it); } : null },
        cols.map((c, i) => h('td', { class: c.main ? 'main-cell' : null, 'data-label': i === 0 ? null : c.label }, c.cell(it))),
        actions ? h('td', { class: 'act' }, h('div', { class: 'actions' }, actions(it))) : null)))));
  }

  function toolbar({ tabs, current, base, search, onSearch, count }) {
    return h('div', { class: 'toolbar' },
      tabs ? h('div', { class: 'seg', role: 'tablist' }, tabs.map(([key, label]) =>
        h('a', { href: `#/${base}?s=${key}`, class: key === current ? 'on' : null, role: 'tab', 'aria-selected': key === current ? 'true' : 'false', text: label }))) : null,
      onSearch ? h('label', { class: 'search' }, icon('search'),
        h('input', { type: 'search', placeholder: search || 'খুঁজুন', 'aria-label': search || 'খুঁজুন', oninput: (e) => onSearch(e.target.value) })) : null,
      count != null ? h('span', { class: 'count', text: `${bn(count)}টা` }) : null);
  }

  const matches = (q, ...fields) => !q || fields.join(' ').toLowerCase().includes(q.trim().toLowerCase());
  const userLink = (uid, text) => (uid ? h('a', { href: `#/user/${encodeURIComponent(uid)}`, text: text || 'ব্যবহারকারী দেখুন' }) : null);
  const small = (...parts) => h('div', { class: 'small' }, parts.filter(Boolean).join('   '));

  // =========================================================
  // ড্যাশবোর্ড
  // =========================================================
  const QUEUES = [
    ['badges', 'badges', 'ব্যাজের আবেদন', 'verified'],
    ['pharmacies', 'pharmacies', 'ফার্মেসি অনুমোদন', 'store'],
    ['ambulances', 'ambulances', 'অ্যাম্বুলেন্স অনুমোদন', 'ambulance'],
    ['bloodBanks', 'bloodBanks', 'ব্লাড ব্যাংক অনুমোদন', 'blood'],
    ['hospitalRequests', 'hospitals', 'হাসপাতাল যোগের অনুরোধ', 'hospital'],
    ['tickets', 'tickets', 'উত্তরের অপেক্ষায় আপিল', 'help'],
    ['reports', 'reports', 'রিপোর্ট', 'flag'],
    ['corrections', 'corrections', 'তারিখ সংশোধন', 'calendar'],
  ];

  async function pageDashboard(r, alive) {
    const total = QUEUES.reduce((a, [k]) => a + pendingCount(k), 0);
    setTitle('ড্যাশবোর্ড', total ? `অপেক্ষায় ${bn(total)}টা কাজ` : 'অপেক্ষায় কোনো কাজ নেই');
    const queues = h('div', { class: 'grid tiles' }, QUEUES.map(([key, path, label, ic]) => {
      const n = pendingCount(key);
      return h('a', { class: `queue${n ? '' : ' zero'}`, href: `#/${path}` },
        h('div', { class: 'badge-ic' }, icon(ic)),
        h('div', null, h('div', { class: 'num', text: bn(n) }), h('div', { class: 'lbl', text: label })));
    }));
    const kpiBox = h('div', { class: 'grid tiles' }, h('p', { class: 'note', text: 'হিসাব আসছে…' }));
    const logBox = h('div', null, h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
    paint(alive,
      h('h2', { class: 'section-title', text: 'অপেক্ষায় যা আছে' }), queues,
      h('h2', { class: 'section-title' }, 'এক নজরে', h('a', { class: 'more', href: '#/analytics', text: 'বিস্তারিত অ্যানালিটিক্স' })), kpiBox,
      h('h2', { class: 'section-title' }, 'সাম্প্রতিক অ্যাডমিন কাজ', h('a', { class: 'more', href: '#/log', text: 'সব দেখুন' })), logBox);

    getStats(false).then((st) => {
      const t = st.totals || {};
      const u = st.users || {};
      const donors = Object.values(st.bloodGroups || {}).reduce((a, b) => a + (b || 0), 0);
      kpiBox.replaceChildren(
        kpi('মোট ব্যবহারকারী', u.total, u.new7 != null ? `গত ৭ দিনে নতুন ${bn(u.new7)}` : null),
        kpi('গত ৭ দিনে সক্রিয়', u.active7, u.active30 != null ? `৩০ দিনে ${bn(u.active30)}` : null),
        kpi('রক্তদাতা (গ্রুপ দেওয়া)', donors),
        kpi('চালু রক্তের অনুরোধ', t.openRequests, t.allRequests != null ? `মোট ${num(t.allRequests)}` : null),
        kpi('অনুমোদিত ডাক্তার', t.doctors),
        kpi('অনুমোদিত নার্স', t.nurses),
        kpi('বাসায় যান: ডাক্তার', t.homeDoctors),
        kpi('বাসায় যান: নার্স', t.homeNurses),
        kpi('তালিকায় ফার্মেসি', t.pharmacies),
        kpi('তালিকায় অ্যাম্বুলেন্স', t.ambulances),
        kpi('তালিকায় ব্লাড ব্যাংক', t.bloodBanks));
    }).catch((e) => kpiBox.replaceChildren(h('p', { class: 'error', text: e.message })));

    act('admin_log').then((res) => {
      logBox.replaceChildren(logTable((res.items || []).slice(0, 6)));
    }).catch((e) => logBox.replaceChildren(h('p', { class: 'error', text: e.message })));
  }

  function kpi(label, value, sub) {
    return h('div', { class: 'card kpi' }, h('div', { class: 'v', text: num(value) }), h('div', { class: 'k', text: label }), sub ? h('div', { class: 'd', text: sub }) : null);
  }

  async function getStats(fresh) {
    if (!fresh && S.stats && Date.now() - S.stats._got < 10 * 60 * 1000) return S.stats;
    const st = await act('admin_stats', fresh ? { fresh: true } : {});
    st._got = Date.now();
    S.stats = st;
    return st;
  }

  // =========================================================
  // অ্যানালিটিক্স
  // =========================================================
  async function pageAnalytics(r, alive, fresh) {
    setTitle('অ্যানালিটিক্স', 'গত ৩০ দিন');
    const st = await getStats(!!fresh);
    if (alive && !alive()) return;
    const t = st.totals || {};
    const u = st.users || {};
    const days = st.days || [];
    const donors = Object.values(st.bloodGroups || {}).reduce((a, b) => a + (b || 0), 0);
    setTitle('অ্যানালিটিক্স', `হিসাব ${ago(st.at)}`, [
      actionBtn('নতুন করে হিসাব', 'quiet', () => pageAnalytics(r, alive, true), null),
    ]);

    const SERIES = [
      ['newUsers', 'নতুন ব্যবহারকারী'],
      ['requests', 'রক্তের অনুরোধ'],
      ['homeCalls', 'বাসায় সেবায় নতুন যোগাযোগ'],
      ['appointments', 'অ্যাপয়েন্টমেন্টের অনুরোধ'],
    ].filter(([k]) => Array.isArray(st.series && st.series[k]));

    const charts = h('div', { class: 'grid three' }, SERIES.map(([k, label]) => barCard(label, st.series[k], days)));
    const dataTable = h('details', { class: 'data' }, h('summary', { text: 'সংখ্যাগুলো টেবিলে দেখুন' }),
      h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
        h('thead', null, h('tr', null, h('th', { text: 'তারিখ' }), SERIES.map(([, l]) => h('th', { text: l })))),
        h('tbody', null, days.slice().reverse().map((d, ri) => {
          const i = days.length - 1 - ri;
          return h('tr', null, h('td', { text: dayFmt.format(new Date(d)) }), SERIES.map(([k, l]) => h('td', { 'data-label': l, text: num(st.series[k][i]) })));
        })))));

    const groups = Object.entries(st.bloodGroups || {});
    const statusCard = (title, map, labels, doneKey, endKeys) => {
      const entries = Object.entries(map || {}).filter(([, v]) => v != null);
      const ended = endKeys.reduce((a, k) => a + ((map || {})[k] || 0), 0);
      const rate = ended ? Math.round(((map[doneKey] || 0) / ended) * 100) : null;
      return h('div', { class: 'card chart-card' }, h('h3', { text: title }),
        h('div', { class: 'note', text: rate != null ? `শেষ হওয়াগুলোর ${bn(rate)}% সম্পন্ন` : 'এখনো কিছু শেষ হয়নি' }),
        hbars(entries.map(([k, v]) => [labels[k] || k, v])));
    };

    paint(alive,
      h('h2', { class: 'section-title', text: 'ব্যবহারকারী' }),
      h('div', { class: 'grid tiles' },
        kpi('মোট ব্যবহারকারী', u.total),
        kpi('গত ৭ দিনে নতুন', u.new7),
        kpi('গত ৩০ দিনে নতুন', u.new30),
        kpi('গত ৭ দিনে সক্রিয়', u.active7),
        kpi('গত ৩০ দিনে সক্রিয়', u.active30),
        kpi('রক্তদাতা (গ্রুপ দেওয়া)', donors, t.usersWithProfile != null ? `প্রোফাইল আছে ${num(t.usersWithProfile)} জনের` : null),
        kpi('বন্ধ করা অ্যাকাউন্ট', u.disabled)),
      h('h2', { class: 'section-title', text: 'দৈনিক কার্যক্রম (গত ৩০ দিন)' }),
      charts, dataTable,
      h('h2', { class: 'section-title', text: 'সেবা ও তালিকা' }),
      h('div', { class: 'grid tiles' },
        kpi('চালু রক্তের অনুরোধ', t.openRequests, t.allRequests != null ? `সব মিলিয়ে ${num(t.allRequests)}` : null),
        kpi('অনুমোদিত ডাক্তার', t.doctors),
        kpi('অনুমোদিত নার্স', t.nurses),
        kpi('বাসায় যান: ডাক্তার', t.homeDoctors),
        kpi('বাসায় যান: নার্স', t.homeNurses),
        kpi('পুষ্টিবিদ ও ট্রেইনার', (t.nutritionists || 0) + (t.trainers || 0)),
        kpi('তালিকায় ফার্মেসি', t.pharmacies),
        kpi('তালিকায় অ্যাম্বুলেন্স', t.ambulances),
        kpi('তালিকায় ব্লাড ব্যাংক', t.bloodBanks),
        kpi('আলোচনার প্রশ্ন', t.questions),
        kpi('স্বাস্থ্য টিপস', t.tips)),
      h('h2', { class: 'section-title', text: 'অবস্থা অনুযায়ী (সব সময়ের)' }),
      h('div', { class: 'grid three' },
        h('div', { class: 'card chart-card' }, h('h3', { text: 'রক্তের গ্রুপ অনুযায়ী দাতা' }), hbars(groups)),
        statusCard('অ্যাপয়েন্টমেন্ট', st.byStatus && st.byStatus.appointments, L.appt, 'confirmed', ['confirmed', 'declined', 'cancelled', 'expired'])),
      h('p', { class: 'note', text: 'হিসাব ৩০ মিনিট পরপর নতুন হয়। "নতুন করে হিসাব" চাপলে এখনই হয় (ডাটাবেসের কিছু পড়া খরচ হয়)।' }));
  }

  /** ৩০ দিনের বার চার্ট — একটাই সিরিজ, তাই লেজেন্ড নেই; ছুঁলে তারিখ আর সংখ্যা */
  function barCard(title, values, days) {
    const vals = values.map((v) => (v == null ? 0 : v));
    const total = vals.reduce((a, b) => a + b, 0);
    const max = Math.max(1, ...vals);
    const W = 300;
    const H = 120;
    const n = vals.length;
    const slot = W / n;
    const bw = Math.max(2, slot - 2); // বারের মাঝে ২px ফাঁক
    const tip = h('div', { class: 'tip', hidden: true });
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', role: 'img', 'aria-label': `${title}: ৩০ দিনে মোট ${bn(total)}` });
    vals.forEach((v, i) => {
      const bh = v ? Math.max(3, (v / max) * (H - 6)) : 0;
      const x = i * slot + 1;
      const y = H - bh;
      if (bh) {
        // উপরের দুই কোণা গোল (৪px), নিচ সমান — শূন্য রেখায় বসানো
        const rr = Math.min(4, bw / 2, bh);
        svg.append(s('path', { class: 'bar', 'data-i': i, d: `M${x},${H}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + bw - rr}Q${x + bw},${y} ${x + bw},${y + rr}V${H}Z` }));
      }
      const hit = s('rect', { class: 'hit', x: i * slot, y: 0, width: slot, height: H });
      const show = () => {
        tip.hidden = false;
        tip.textContent = `${dayFmt.format(new Date(days[i]))}: ${bn(v)}`;
        tip.style.left = `${((i + 0.5) / n) * 100}%`;
        svg.querySelectorAll('.bar').forEach((b) => b.classList.toggle('hl', Number(b.getAttribute('data-i')) === i));
      };
      hit.addEventListener('pointerenter', show);
      hit.addEventListener('pointerdown', show);
      svg.append(hit);
    });
    svg.append(s('line', { class: 'base', x1: 0, x2: W, y1: H - 0.5, y2: H - 0.5 }));
    svg.addEventListener('pointerleave', () => {
      tip.hidden = true;
      svg.querySelectorAll('.bar.hl').forEach((b) => b.classList.remove('hl'));
    });
    const peak = vals.indexOf(Math.max(...vals));
    return h('div', { class: 'card chart-card' },
      h('h3', { text: title }),
      h('div', { class: 'total' }, bn(total), h('small', { text: total ? `সর্বোচ্চ ${bn(vals[peak])}, ${dayFmt.format(new Date(days[peak]))}` : 'এই সময়ে কিছু নেই' })),
      h('div', { class: 'chart' }, tip, svg,
        h('div', { class: 'x' }, h('span', { text: dayFmt.format(new Date(days[0])) }), h('span', { text: 'আজ' }))));
  }

  function hbars(rows) {
    const max = Math.max(1, ...rows.map(([, v]) => v || 0));
    return h('div', { class: 'hbars' }, rows.map(([label, v]) => h('div', { class: 'hbar' },
      h('span', { text: label }),
      h('div', { class: 'track' }, (() => {
        const f = h('div', { class: 'fill' });
        f.style.width = `${((v || 0) / max) * 100}%`;
        return f;
      })()),
      h('span', { class: 'val', text: num(v) }))));
  }

  // =========================================================
  // জরুরি রক্তের আহ্বান (দুর্ঘটনা ইত্যাদি)
  // =========================================================
  const GROUPS = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];

  async function pageEmergency(r, alive) {
    setTitle('জরুরি আহ্বান', 'দুর্ঘটনায় একসাথে অনেক রক্ত');
    const res = await act('admin_emergencies');
    if (alive && !alive()) return;
    const items = res.items || [];
    const live = items.filter((x) => x.live);
    const past = items.filter((x) => !x.live);

    const card = (x) => {
      const rows = Object.entries(x.groups || {}).map(([g, v]) => {
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

    paint(alive,
      h('p', { class: 'note', text: 'দুর্ঘটনা বা বড় জরুরি অবস্থায় হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে আহ্বান দিন। প্রতিটা গ্রুপে যত ব্যাগ দরকার তার প্রায় ৩ গুণ কাছের যোগ্য ডোনারকে আগে জানানো হয়; যথেষ্ট মানুষ "আসছি" বললে সেই গ্রুপ নিজে থেকে বন্ধ হয়, যাতে হাসপাতালে ভিড় না হয়।' }),
      live.length ? [h('h2', { class: 'section-title', text: 'চালু আহ্বান' }), ...live.map(card)] : null,
      h('h2', { class: 'section-title', text: 'নতুন আহ্বান' }),
      emergencyForm(),
      past.length ? [h('h2', { class: 'section-title', text: 'আগের আহ্বান' }), ...past.map(card)] : null);
  }

  function emergencyForm() {
    const field = (label, el) => h('label', { class: 'field' }, h('span', { text: label }), el);
    const district = h('select', { 'aria-label': 'জেলা' }, h('option', { value: '', text: 'জেলা বেছে নিন' }));
    const hospital = h('select', { 'aria-label': 'হাসপাতাল', disabled: true }, h('option', { value: '', text: 'আগে জেলা বেছে নিন' }));
    const hospitalText = h('input', { maxlength: 120, placeholder: 'তালিকায় না থাকলে নাম লিখুন' });
    act('admin_districts').then((r) => (r.items || []).forEach((d) => district.append(h('option', { value: d, text: d })))).catch(() => {});
    district.addEventListener('change', async () => {
      hospital.replaceChildren(h('option', { value: '', text: 'লোড হচ্ছে…' }));
      hospital.disabled = true;
      try {
        const r = await act('admin_hospitals', { district: district.value });
        hospital.replaceChildren(h('option', { value: '', text: 'হাসপাতাল বেছে নিন' }), ...(r.items || []).map((x) => h('option', { value: x.id, text: x.name })));
        hospital.disabled = false;
      } catch (e) {
        hospital.replaceChildren(h('option', { value: '', text: 'আনা যায়নি' }));
      }
    });
    const loc = h('input', { placeholder: 'যেমন 24.7471, 90.4203 (Google Maps থেকে)', maxlength: 60 });
    const needs = GROUPS.map((g) => ({ g, el: h('input', { type: 'number', min: 0, max: 200, value: '', placeholder: '০', 'aria-label': `${g} কত ব্যাগ` }) }));
    const radius = h('select', null, [10, 25, 50].map((k) => h('option', { value: k, text: `${bn(k)} কিমি`, selected: k === 25 })));
    const hours = h('select', null, [6, 12, 24, 48].map((k) => h('option', { value: k, text: `${bn(k)} ঘণ্টা`, selected: k === 12 })));
    const phone = h('input', { inputmode: 'tel', maxlength: 15, placeholder: '01XXXXXXXXX' });
    const note = h('input', { maxlength: 300, placeholder: 'যেমন: বাস দুর্ঘটনা, জরুরি বিভাগের ব্লাড ব্যাংকে আসুন' });
    const confirm = h('input', { type: 'checkbox' });
    return h('div', { class: 'card pad bank-form' },
      h('div', { class: 'field-row' }, field('জেলা', district), field('হাসপাতাল', hospital)),
      field('হাসপাতাল (তালিকায় না থাকলে)', hospitalText),
      field('হাসপাতালের লোকেশন (ঐচ্ছিক, দূরত্ব ঠিকভাবে মাপতে)', loc),
      h('div', { class: 'field' }, h('span', { text: 'কোন গ্রুপের কত ব্যাগ দরকার' }),
        h('div', { class: 'need-grid' }, needs.map(({ g, el }) => h('label', { class: 'need' }, h('b', { text: g }), el)))),
      h('div', { class: 'field-row' }, field('কত দূর পর্যন্ত জানানো হবে', radius), field('কতক্ষণ চালু থাকবে', hours)),
      field('হাসপাতালের যোগাযোগের মোবাইল নম্বর', phone),
      field('বার্তা (ঐচ্ছিক)', note),
      h('label', { class: 'check' }, confirm, h('span', { text: 'হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছি যে এই রক্ত দরকার' })),
      h('div', { class: 'actions' }, actionBtn('আহ্বান পাঠান', 'warn', async () => {
        const groups = {};
        needs.forEach(({ g, el }) => { const n = parseInt(el.value, 10); if (n > 0) groups[g] = n; });
        let lat;
        let lng;
        const m = loc.value.match(/(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/);
        if (m) { lat = parseFloat(m[1]); lng = parseFloat(m[2]); }
        const r = await act('admin_emergency_create', {
          district: district.value, hospitalId: hospital.value || null, hospitalName: hospitalText.value.trim(),
          lat, lng, groups, radiusKm: parseInt(radius.value, 10), hours: parseInt(hours.value, 10),
          contactPhone: phone.value.trim(), note: note.value.trim(), confirmed: confirm.checked,
        });
        toast(`${bn(r.notified || 0)} জন ডোনারকে জানানো হয়েছে`);
      }, 'আহ্বান চালু হয়েছে', afterChange)));
  }

  // =========================================================
  // অনুমোদনের তালিকা: ব্যাজ, ফার্মেসি, অ্যাম্বুলেন্স
  // =========================================================
  const LISTINGS = {
    badges: {
      title: 'ব্যাজ',
      action: 'admin_badges',
      tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['rejected', 'বাতিল']],
      search: 'নাম, ইমেইল, রেজি. নম্বর',
      text: (b) => [b.name, b.email, b.regNo, b.workplace, L.badge[b.type], ...(b.change ? [b.change.name, b.change.regNo, b.change.workplace] : [])],
      cols: [
        // অনুমোদিত ব্যাজের সংশোধনের অনুরোধ: নতুন তথ্য বড় করে, আগেরটা নিচে ছোট করে
        { label: 'নাম', main: true, cell: (b) => b.change
          ? [b.change.name || '—', ' ', h('span', { class: 'chip bad', text: 'সংশোধন' }), ' ', h('span', { class: 'chip pri', text: L.badge[b.change.type] || b.change.type }),
            b.change.name !== b.name ? h('div', { class: 'small', text: `আগে: ${b.name || '—'}` }) : null]
          : [b.name || '—', ' ', h('span', { class: 'chip pri', text: L.badge[b.type] || b.type })] },
        { label: 'ইমেইল', cell: (b) => b.email || '—' },
        { label: 'রেজি. নম্বর', cell: (b) => [(b.change ? b.change.regNo : b.regNo) || '—',
          b.change && b.change.regNo !== b.regNo ? h('div', { class: 'small', text: `আগে: ${b.regNo || '—'}` }) : null, h('div', { class: 'small' },
          b.type === 'doctor' ? h('a', { href: 'https://www.bmdc.org.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BMDC-তে মেলান' })
            : b.type === 'nurse' ? h('a', { href: 'https://www.bnmc.gov.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BNMC-তে মেলান' })
              : 'সনদ দেখে নিন')] },
        { label: 'কর্মস্থল', cell: (b) => [(b.change ? b.change.workplace : b.workplace) || '—',
          b.change && b.change.workplace !== b.workplace ? h('div', { class: 'small', text: `আগে: ${b.workplace || '—'}` }) : null] },
        { label: 'তারিখ', cell: (b) => fmtDate(b.change ? b.change.at : b.reviewedAt || b.createdAt) },
        { label: 'কাগজ / যাচাই', cell: (b) => reviewCell(b) },
      ],
      id: (b) => b.uid + (b.change ? ':change' : ''),
      owner: (b) => b.uid,
      actions: (b, st) => {
        const reason = reasonBox();
        const run = (approve) => () => act('admin_badge_resolve', { uid: b.uid, approve, reason: reason.value.trim() });
        // অনুমোদন শুধু যাচাইয়ের পাতা থেকে (কাগজ দেখে সব টিক দিয়ে)
        const verifyBtn = (label) => h('a', { class: 'btn primary', href: `#/verify/badges:${encodeURIComponent(b.uid + (b.change ? ':change' : ''))}`, text: label });
        if (st === 'pending' && b.change) {
          const fix = (approve) => () => act('admin_badge_resolve', { uid: b.uid, approve, correction: true });
          return [verifyBtn('যাচাই করে মঞ্জুর'), actionBtn('সংশোধন বাতিল', 'warn', fix(false), 'অনুরোধ বাতিল, ব্যাজ আগের মতো', afterChange)];
        }
        if (st === 'pending') return [reason, verifyBtn('যাচাই করে অনুমোদন'), actionBtn('বাতিল', 'warn', run(false), 'আবেদন বাতিল করা হয়েছে', afterChange)];
        if (st === 'approved') return [reason, actionBtn('ব্যাজ বাতিল', 'warn', run(false), 'ব্যাজ বাতিল করা হয়েছে, তালিকা থেকেও সরেছে', afterChange)];
        return [verifyBtn('যাচাই করে অনুমোদন')];
      },
    },
    pharmacies: {
      title: 'ফার্মেসি',
      action: 'admin_pharmacies',
      tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
      search: 'নাম, ফোন, লাইসেন্স, এলাকা',
      text: (p) => [p.name, p.phone, p.licenseNo, p.areaLabel, p.district, p.address],
      cols: [
        { label: 'নাম', main: true, cell: (p) => [p.name || '—', p.rating ? h('div', { class: 'small', text: `রেটিং ${bn(p.rating)} (${bn(p.ratingCount)})` }) : null] },
        { label: 'লাইসেন্স', cell: (p) => p.licenseNo || '—' },
        { label: 'ফোন', cell: (p) => bn(p.phone) },
        { label: 'এলাকা', cell: (p) => [p.areaLabel || p.district || '—', p.address ? h('div', { class: 'small', text: p.address }) : null] },
        { label: 'সময়', cell: (p) => [p.open24 ? '২৪ ঘণ্টা' : p.openAt ? `${bn(p.openAt)}–${bn(p.closeAt)}` : '—',
          (p.closedDays || []).length ? h('div', { class: 'small', text: `বন্ধ: ${p.closedDays.map((d) => WEEKDAYS[d]).join(', ')}` }) : null] },
        { label: 'তারিখ', cell: (p) => fmtDate(p.reviewedAt || p.createdAt) },
        { label: 'বদল / নোট', cell: (p) => reviewCell(p) },
      ],
      note: 'অনুমোদনের আগে ঔষধ প্রশাসন অধিদপ্তরে (DGDA) লাইসেন্স যাচাই করুন।',
      id: (p) => p.id,
      owner: (p) => p.id,
      actions: (p, st) => statusActions('admin_pharmacy_resolve', p.id, st, 'ফার্মেসি'),
    },
    ambulances: {
      title: 'অ্যাম্বুলেন্স',
      action: 'admin_ambulances',
      tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
      search: 'নাম, ফোন, গাড়ির নম্বর, এলাকা',
      text: (a) => [a.name, a.phone, a.vehicleNo, a.areaLabel, a.district, a.area],
      cols: [
        { label: 'নাম', main: true, cell: (a) => [a.name || '—', h('div', { class: 'chips' }, (a.types || []).map((t) => h('span', { class: 'chip', text: L.amb[t] || t })))] },
        { label: 'গাড়ির নম্বর', cell: (a) => a.vehicleNo || '—' },
        { label: 'ফোন', cell: (a) => bn(a.phone) },
        { label: 'এলাকা', cell: (a) => [a.areaLabel || a.district || '—', a.area ? h('div', { class: 'small', text: a.area }) : null] },
        { label: 'ভাড়া', cell: (a) => a.fare || '—' },
        { label: 'রেটিং', cell: (a) => (a.ratingCount ? `${bn(a.rating)} (${bn(a.ratingCount)})` : '—') },
        { label: 'বদল / নোট', cell: (a) => reviewCell(a) },
      ],
      id: (a) => a.id,
      owner: (a) => a.ownerUid,
      actions: (a, st) => statusActions('admin_ambulance_resolve', a.id, st, 'অ্যাম্বুলেন্স'),
    },
    bloodBanks: {
      title: 'ব্লাড ব্যাংক',
      action: 'admin_bloodbanks',
      tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
      search: 'নাম, ফোন, লাইসেন্স, এলাকা',
      text: (b) => [b.name, b.phone, b.licenseNo, b.areaLabel, b.district, b.address],
      cols: [
        { label: 'নাম', main: true, cell: (b) => [b.name || '—', ' ', h('span', { class: 'chip pri', text: L.bankKind[b.kind] || b.kind }),
          b.addedByAdmin ? [' ', h('span', { class: 'chip', text: 'অ্যাডমিন যোগ করেছেন' })] : null,
          h('div', { class: 'chips' }, (b.services || []).map((x) => h('span', { class: 'chip', text: L.bankService[x] || x })))] },
        { label: 'লাইসেন্স', cell: (b) => b.licenseNo || '—' },
        { label: 'ফোন', cell: (b) => bn(b.phone) },
        { label: 'এলাকা', cell: (b) => [b.areaLabel || b.district || '—', b.address ? h('div', { class: 'small', text: b.address }) : null] },
        { label: 'সময়', cell: (b) => [b.open24 ? '২৪ ঘণ্টা' : b.openAt ? `${bn(b.openAt)}–${bn(b.closeAt)}` : '—',
          (b.closedDays || []).length ? h('div', { class: 'small', text: `বন্ধ: ${b.closedDays.map((d) => WEEKDAYS[d]).join(', ')}` }) : null] },
        { label: 'তারিখ', cell: (b) => fmtDate(b.reviewedAt || b.createdAt) },
        { label: 'বদল / নোট', cell: (b) => reviewCell(b) },
      ],
      note: 'বেসরকারি ব্লাড ব্যাংকের লাইসেন্স স্বাস্থ্য অধিদপ্তরে যাচাই করুন। হাসপাতালের ব্লাড ব্যাংক হলে হাসপাতালে ফোন করে মিলিয়ে নিন।',
      id: (b) => b.id,
      owner: (b) => b.ownerUid,
      actions: (b, st) => [...statusActions('admin_bloodbank_resolve', b.id, st, 'ব্লাড ব্যাংক'),
        h('button', { type: 'button', class: 'btn quiet', text: 'সম্পাদনা', onclick: () => { S.editBank = b; route(true); setTimeout(() => document.querySelector('.bank-form')?.scrollIntoView({ behavior: 'smooth' }), 50); } }),
        actionBtn('মুছুন', 'warn', () => act('admin_bloodbank_delete', { id: b.id }), 'ব্লাড ব্যাংক মুছে ফেলা হয়েছে', afterChange)],
      extra: () => bloodBankForm(S.editBank || null),
    },
  };

  /** অ্যাডমিন নিজে চেনা ব্লাড ব্যাংক যোগ করেন (সাথে সাথে তালিকায়), বা কোনোটার তথ্য ঠিক করেন (edit) */
  function bloodBankForm(edit) {
    const e = edit || {};
    const input = (name, label, attrs = {}) => h('label', { class: 'field' }, h('span', { text: label }), h('input', { name, value: e[name] ?? '', ...attrs }));
    const district = h('select', { name: 'district', 'aria-label': 'জেলা' }, h('option', { value: '', text: 'জেলা বেছে নিন' }));
    act('admin_districts').then((r) => {
      (r.items || []).forEach((d) => district.append(h('option', { value: d, text: d })));
      if (e.district) district.value = e.district;
    }).catch(() => {});
    const kind = h('select', { name: 'kind', 'aria-label': 'ধরন' }, Object.entries(L.bankKind).map(([k, v]) => h('option', { value: k, text: v })));
    if (e.kind) kind.value = e.kind;
    const open24 = h('input', { type: 'checkbox', name: 'open24' });
    open24.checked = e.open24 === true;
    const services = Object.entries(L.bankService).map(([k, v]) => {
      const c = h('input', { type: 'checkbox', name: 'svc', value: k });
      c.checked = (e.services || []).includes(k);
      return h('label', { class: 'check' }, c, h('span', { text: v }));
    });
    const days = WEEKDAYS.map((v, i) => {
      const c = h('input', { type: 'checkbox', name: 'off', value: String(i) });
      c.checked = (e.closedDays || []).includes(i);
      return h('label', { class: 'check' }, c, h('span', { text: v }));
    });
    const body = (f) => ({
      name: String(f.get('name') || '').trim(), kind: f.get('kind'), phone: String(f.get('phone') || '').trim(),
      district: f.get('district'), address: String(f.get('address') || '').trim(), licenseNo: String(f.get('licenseNo') || '').trim(),
      open24: open24.checked, openAt: f.get('openAt') || '', closeAt: f.get('closeAt') || '', services: f.getAll('svc'),
      closedDays: f.getAll('off').map(Number),
    });
    const form = h('form', { class: 'card pad bank-form', onsubmit: (ev) => ev.preventDefault() },
      input('name', 'নাম', { maxlength: 100, required: true }),
      h('label', { class: 'field' }, h('span', { text: 'ধরন' }), kind),
      input('phone', 'ফোন (মোবাইল, ল্যান্ডলাইন বা হটলাইন)', { inputmode: 'tel', maxlength: 15 }),
      h('label', { class: 'field' }, h('span', { text: 'জেলা' }), district),
      input('address', 'ঠিকানা', { maxlength: 200 }),
      input('licenseNo', 'লাইসেন্স নম্বর (বেসরকারি হলে লাগবে)', { maxlength: 60 }),
      h('label', { class: 'check' }, open24, h('span', { text: '২৪ ঘণ্টা খোলা' })),
      h('div', { class: 'field-row' }, input('openAt', 'খোলে', { type: 'time' }), input('closeAt', 'বন্ধ হয়', { type: 'time' })),
      h('div', { class: 'field' }, h('span', { text: 'সাপ্তাহিক বন্ধ' }), h('div', { class: 'checks' }, days)),
      h('div', { class: 'field' }, h('span', { text: 'সেবা' }), h('div', { class: 'checks' }, services)),
      edit
        ? h('div', { class: 'actions' },
          actionBtn('সংরক্ষণ', 'primary', async () => {
            await act('admin_bloodbank_edit', { id: edit.id, ...body(new FormData(form)) });
            S.editBank = null;
          }, 'তথ্য ঠিক করা হয়েছে', afterChange),
          h('button', { type: 'button', class: 'btn quiet', text: 'বাদ দিন', onclick: () => { S.editBank = null; route(true); } }))
        : actionBtn('যোগ করুন', 'primary', async () => {
          await act('admin_bloodbank_add', body(new FormData(form)));
          form.reset();
        }, 'ব্লাড ব্যাংক যোগ হয়েছে, তালিকায় দেখাচ্ছে', afterChange));
    return edit
      ? [h('h2', { class: 'section-title', text: `সম্পাদনা: ${edit.name}` }),
        h('p', { class: 'note', text: 'মালিক থাকলে তাঁকে জানানো হবে যে Lifolo টিম তথ্য ঠিক করেছে।' }), form]
      : [h('h2', { class: 'section-title', text: 'চেনা ব্লাড ব্যাংক নিজে যোগ করুন' }),
        h('p', { class: 'note', text: 'যেমন সরকারি হাসপাতালের ব্লাড ব্যাংক বা সন্ধানী/রেড ক্রিসেন্ট। যোগ করলেই অ্যাপের তালিকায় দেখাবে।' }), form];
  }

  const WEEKDAYS = ['রবি', 'সোম', 'মঙ্গল', 'বুধ', 'বৃহস্পতি', 'শুক্র', 'শনি'];
  const FIELD_LABEL = {
    name: 'নাম', phone: 'ফোন', vehicleNo: 'গাড়ির নম্বর', licenseNo: 'লাইসেন্স', district: 'জেলা', upazilaId: 'উপজেলা', address: 'ঠিকানা',
    kind: 'ধরন', types: 'ধরন', area: 'এলাকা', fare: 'ভাড়া', openAt: 'খোলে', closeAt: 'বন্ধ হয়', open24: '২৪ ঘণ্টা', services: 'সেবা',
    closedDays: 'সাপ্তাহিক বন্ধ', delivery: 'হোম ডেলিভারি', deliveryNote: 'ডেলিভারির কথা', discount: 'ছাড়', lat: 'লোকেশন',
  };
  function showVal(field, v) {
    if (v == null || v === '' || (Array.isArray(v) && !v.length)) return '—';
    if (field === 'closedDays') return v.map((d) => WEEKDAYS[d]).join(', ');
    if (typeof v === 'boolean') return v ? 'হ্যাঁ' : 'না';
    if (Array.isArray(v)) return v.map((x) => L.amb[x] || L.bankService[x] || x).join(', ');
    return bn(v);
  }
  /** অনুমোদিত তথ্যের পর কী বদলেছে, কোন কারণে বাতিল/বন্ধ, আর একই ফোন/গাড়ি/লাইসেন্সের অন্য তালিকা */
  function reviewCell(x) {
    const out = [];
    if (x.verify) {
      const req = x.verify.docs.filter((d) => d.required);
      const miss = req.filter((d) => !d.has);
      const got = x.verify.docs.filter((d) => d.has).length;
      out.push(h('div', { class: 'chips' },
        h('span', { class: `chip ${miss.length ? 'bad' : 'good'}`, text: miss.length ? `কাগজ বাকি: ${bn(miss.length)}টা` : `কাগজ: ${bn(got)}টা` }),
        x.verify.docsRequestedAt ? h('span', { class: 'chip', text: `ছবি চাওয়া ${fmtDate(x.verify.docsRequestedAt)}` }) : null));
    }
    if (x.verification) out.push(h('div', { class: 'small', text: `যাচাই: ${x.verification.byEmail || '—'} · ${fmtDate(x.verification.at)}` }));
    if (x.statusReason) out.push(h('div', { class: 'small', text: `কারণ: ${x.statusReason}` }));
    if (x.editRejectedReason) out.push(h('div', { class: 'small', text: `শেষ বদল বাতিলের কারণ: ${x.editRejectedReason}` }));
    (x.changes || []).filter((c) => c.field !== 'lng').forEach((c) => out.push(h('div', { class: 'small' },
      h('b', { text: `${FIELD_LABEL[c.field] || c.field}: ` }), c.field === 'lat' ? 'বদলেছে' : `আগে ${showVal(c.field, c.before)} → এখন ${showVal(c.field, c.after)}`)));
    (x.duplicates || []).forEach((d) => out.push(h('div', { class: 'small' },
      h('span', { class: 'chip bad', text: `একই ${FIELD_LABEL[d.field] || d.field}` }), ' ', `${d.name || '—'} (${L.listing[d.status] || d.status})`)));
    return out.length ? out : '—';
  }

  const REASONS = ['লাইসেন্স যাচাই করা যায়নি', 'ফোন নম্বর কাজ করে না', 'তথ্য অসম্পূর্ণ বা ভুল', 'একই তালিকা আগে থেকে আছে', 'রিপোর্ট সত্য পাওয়া গেছে', 'নিয়ম ভঙ্গ হয়েছে'];
  /** বাতিল/বন্ধের কারণ — মালিক নোটিফিকেশনে আর নিজের কার্ডে দেখেন। তালিকা থেকে বাছা বা নিজে লেখা */
  function reasonBox() {
    if (!document.getElementById('reason-list')) {
      document.body.append(h('datalist', { id: 'reason-list' }, REASONS.map((r) => h('option', { value: r }))));
    }
    return h('input', { class: 'reason', list: 'reason-list', maxlength: 200, placeholder: 'কারণ (মালিক দেখবেন)', 'aria-label': 'কারণ' });
  }

  function statusActions(action, id, st, what) {
    const reason = reasonBox();
    const run = (status) => () => act(action, { id, status, reason: reason.value.trim() });
    // অনুমোদন শুধু যাচাইয়ের পাতা থেকে (কাগজ দেখে সব টিক দিয়ে)
    const kind = { admin_pharmacy_resolve: 'pharmacies', admin_ambulance_resolve: 'ambulances', admin_bloodbank_resolve: 'bloodBanks' }[action];
    const verifyBtn = h('a', { class: 'btn primary', href: `#/verify/${kind}:${encodeURIComponent(id)}`, text: 'যাচাই করে অনুমোদন' });
    if (st === 'pending') return [reason, verifyBtn, actionBtn('বাতিল', 'warn', run('rejected'), `${what} বাতিল করা হয়েছে`, afterChange)];
    if (st === 'approved') return [reason, actionBtn('বন্ধ করুন', 'warn', run('suspended'), `${what} বন্ধ করা হয়েছে`, afterChange)];
    if (st === 'suspended') return [actionBtn('আবার চালু করুন', 'tonal', run('approved'), `${what} আবার চালু হয়েছে`, afterChange)];
    return [verifyBtn];
  }

  async function getList(kind, status) {
    const key = `${kind}:${status}`;
    if (S.lists[key]) return S.lists[key];
    const action = { badges: 'admin_badges', pharmacies: 'admin_pharmacies', ambulances: 'admin_ambulances', bloodBanks: 'admin_bloodbanks', tickets: 'admin_tickets' }[kind];
    const res = await act(action, { status });
    S.lists[key] = res.items || [];
    return S.lists[key];
  }

  async function pageListing(kind, r, alive) {
    const def = LISTINGS[kind];
    const st = def.tabs.some(([k]) => k === r.params.get('s')) ? r.params.get('s') : 'pending';
    setTitle(def.title, L.listing[st]);
    const items = await getList(kind, st);
    let q = '';
    const box = h('div');
    const countEl = h('span', { class: 'count' });
    const draw = () => {
      const shown = items.filter((x) => matches(q, ...def.text(x)));
      countEl.textContent = `${bn(shown.length)}টা`;
      box.replaceChildren(table(shown, [...def.cols, { label: '', cell: (x) => userLink(def.owner(x), 'ব্যবহারকারী') }], (x) => def.actions(x, st)));
    };
    const tb = toolbar({ tabs: def.tabs, current: st, base: kind, search: def.search, onSearch: (v) => { q = v; draw(); } });
    tb.append(countEl);
    draw();
    paint(alive, tb, def.note && st === 'pending' ? h('p', { class: 'note', text: def.note }) : null, box, def.extra ? def.extra() : null);
  }

  // =========================================================
  // যাচাই: কাগজের ছবি দেখে সব বিষয় টিক দিয়ে তবে অনুমোদন
  // #/verify/pharmacies:<id>, #/verify/badges:<uid>[:change]
  // =========================================================
  const VERIFY_KIND = { pharmacies: 'pharmacy', ambulances: 'ambulance', bloodBanks: 'bloodbank', badges: 'badge' };
  const RESOLVE = { pharmacies: 'admin_pharmacy_resolve', ambulances: 'admin_ambulance_resolve', bloodBanks: 'admin_bloodbank_resolve' };

  /** আবেদনে যা লেখা হয়েছে (ছবির সাথে মেলানোর জন্য) */
  function verifyFields(list, x) {
    const c = x.change || {};
    const area = [x.areaLabel || x.district, x.address || x.area].filter(Boolean).join(' · ');
    const hours = x.open24 ? '২৪ ঘণ্টা' : x.openAt ? `${bn(x.openAt)}–${bn(x.closeAt)}` : '';
    if (list === 'badges') {
      return [['নাম', c.name || x.name], ['পেশা', L.badge[c.type || x.type] || c.type || x.type], ['রেজি. নম্বর', c.regNo || x.regNo],
        ['কর্মস্থল', c.workplace || x.workplace], ['ইমেইল', x.email]];
    }
    if (list === 'ambulances') {
      return [['নাম', x.name], ['গাড়ির নম্বর', x.vehicleNo], ['ধরন', (x.types || []).map((t) => L.amb[t] || t).join(', ')],
        ['ফোন', bn(x.phone)], ['এলাকা', area], ['ভাড়া', x.fare]];
    }
    if (list === 'pharmacies') {
      return [['নাম', x.name], ['লাইসেন্স নম্বর', x.licenseNo], ['ফোন', bn(x.phone)], ['এলাকা ও ঠিকানা', area], ['সময়', hours]];
    }
    return [['নাম', x.name], ['ধরন', L.bankKind[x.kind] || x.kind], ['লাইসেন্স নম্বর', x.licenseNo], ['ফোন', bn(x.phone)],
      ['এলাকা ও ঠিকানা', area], ['সময়', hours]];
  }

  async function findForVerify(list, key) {
    const match = (it) => (list === 'badges' ? it.uid + (it.change ? ':change' : '') : it.id) === key;
    for (const st of ['pending', 'rejected']) {
      const items = await getList(list, st);
      const it = items.find(match);
      if (it) return { item: it, st };
    }
    return null;
  }

  /** ছবি বড় করে দেখা (পর্দাজুড়ে, চাপলে বন্ধ) */
  function zoomPhoto(src, label) {
    const ov = h('div', { class: 'photo-zoom', role: 'dialog', 'aria-label': label, onclick: () => ov.remove() },
      h('img', { src, alt: label }), h('div', { class: 'cap', text: `${label} — বন্ধ করতে চাপুন` }));
    document.body.append(ov);
  }

  async function pageVerify(r, alive) {
    const [list, ...rest] = r.arg.split(':');
    const key = rest.join(':');
    const kind = VERIFY_KIND[list];
    if (!kind || !key) throw new Error('পাওয়া যায়নি');
    const back = `#/${list}`;
    setTitle('যাচাই', LISTINGS[list].title, [h('a', { class: 'btn quiet', href: back, text: 'ফিরে যান' })]);
    const found = await findForVerify(list, key);
    if (!found) {
      paint(alive, h('div', { class: 'card pad' }, h('p', { text: 'এটা আর অপেক্ষার তালিকায় নেই (হয়তো আগেই সিদ্ধান্ত হয়েছে)।' }), h('a', { href: back, text: 'তালিকায় ফিরুন' })));
      return;
    }
    const x = found.item;
    const id = list === 'badges' ? x.uid : x.id;
    const correction = list === 'badges' && !!x.change;
    const v = await act('admin_verify_docs', { kind, id });
    if (alive && !alive()) return;

    // ছবি
    const photos = h('div', { class: 'photos' }, v.photos.map((p) => {
      const meta = v.docs.find((d) => d.type === p.type) || {};
      if (!p.data) {
        return h('figure', { class: 'photo none' }, h('div', { class: 'ph', text: meta.required ? 'দরকারি ছবি দেওয়া হয়নি' : 'দেওয়া হয়নি (ঐচ্ছিক)' }),
          h('figcaption', null, p.label, meta.required ? [' ', h('span', { class: 'chip bad', text: 'দরকারি' })] : null));
      }
      const src = `data:image/jpeg;base64,${p.data}`;
      return h('figure', { class: 'photo' },
        h('img', { src, alt: p.label, onclick: () => zoomPhoto(src, p.label) }),
        h('figcaption', null, p.label, h('span', { class: 'small', text: ` · ${fmtDate(p.at)} · বড় করতে চাপুন` })));
    }));
    const missing = v.docs.filter((d) => d.required && !d.has);

    // লেখা তথ্য
    const fields = h('table', { class: 'kv' }, h('tbody', null, verifyFields(list, x).map(([k, val]) =>
      h('tr', null, h('th', { scope: 'row', text: k }), h('td', { text: val || '—' })))));

    // টিক দেওয়ার তালিকা
    const ticked = new Set();
    const approveBtn = h('button', { type: 'button', class: 'btn primary', disabled: true, text: correction ? 'সংশোধন মঞ্জুর' : 'অনুমোদন দিন' });
    const left = h('span', { class: 'small' });
    const refresh = () => {
      const n = v.checks.length - ticked.size;
      approveBtn.disabled = n > 0 || missing.length > 0;
      left.textContent = missing.length ? 'দরকারি ছবি নেই — আগে ছবি চান' : n > 0 ? `আরও ${bn(n)}টা বিষয় বাকি` : 'সব মিলেছে';
    };
    const checks = h('ol', { class: 'checklist' }, v.checks.map((c) => {
      const box = h('input', { type: 'checkbox', id: `ck_${c.id}`, onchange: (e) => { if (e.target.checked) ticked.add(c.id); else ticked.delete(c.id); refresh(); } });
      return h('li', null, h('label', { for: `ck_${c.id}` }, box, h('span', { class: 'lbl', text: c.label })),
        c.format ? h('div', { class: 'fmt' }, h('b', { text: 'সঠিক রূপ: ' }), c.format) : null,
        c.link ? h('div', { class: 'fmt' }, h('a', { href: c.link, target: '_blank', rel: 'noopener noreferrer', text: 'এখানে মিলিয়ে দেখুন ↗' })) : null);
    }));
    refresh();

    const done = async () => {
      // অপেক্ষার তালিকা loadOverview নতুন করে দেয়; বাকিগুলো আবার আনা হবে
      for (const k of Object.keys(S.lists)) delete S.lists[k];
      await loadOverview().catch(() => {});
      go(back);
    };
    approveBtn.addEventListener('click', async () => {
      approveBtn.disabled = true;
      try {
        if (list === 'badges') await act('admin_badge_resolve', { uid: id, approve: true, checks: [...ticked], ...(correction ? { correction: true } : {}) });
        else await act(RESOLVE[list], { id, status: 'approved', checks: [...ticked] });
        toast('যাচাই করে অনুমোদন দেওয়া হয়েছে');
        await done();
      } catch (e) {
        toast(e.message);
        refresh();
      }
    });

    const reason = reasonBox();
    const reject = actionBtn(correction ? 'সংশোধন বাতিল' : 'বাতিল', 'warn', () => (list === 'badges'
      ? act('admin_badge_resolve', { uid: id, approve: false, reason: reason.value.trim(), ...(correction ? { correction: true } : {}) })
      : act(RESOLVE[list], { id, status: 'rejected', reason: reason.value.trim() })), 'বাতিল করা হয়েছে', done);
    const note = h('input', { class: 'reason', maxlength: 200, placeholder: 'কোন ছবি লাগবে (মালিক দেখবেন)', 'aria-label': 'কোন ছবি লাগবে' });
    const askDocs = actionBtn('ছবি চান', 'tonal', () => act('admin_request_docs', { kind, id, note: note.value.trim() }), 'মালিককে ছবি দিতে বলা হয়েছে', done);
    // মালিক আছে কি না (অ্যাডমিনের যোগ করা ব্লাড ব্যাংকের মালিক নেই)
    const hasOwner = list === 'badges' || list === 'pharmacies' || !!x.ownerUid;

    paint(alive,
      h('div', { class: 'verify' },
        h('div', { class: 'card pad' }, h('h2', { class: 'section-title', text: 'কাগজের ছবি' }), photos),
        h('div', { class: 'card pad' },
          h('h2', { class: 'section-title', text: 'আবেদনে যা লেখা' }), fields,
          reviewCell(x) === '—' ? null : h('div', { class: 'note' }, reviewCell(x)),
          h('h2', { class: 'section-title', text: 'ছবি দেখে মিলিয়ে টিক দিন' }), checks,
          h('div', { class: 'row' }, approveBtn, left)),
        // আলাদা কার্ড: এখানকার বাটনের ভুলে অনুমোদনের বাটন খুলে যায় না
        h('div', { class: 'card pad verify-side' },
          h('h2', { class: 'section-title', text: 'না মিললে' }),
          h('div', { class: 'actions' }, reason, reject),
          hasOwner ? h('div', { class: 'actions' }, note, askDocs) : h('p', { class: 'note', text: 'এটা অ্যাডমিন যোগ করেছেন, মালিক নেই।' }),
          hasOwner ? h('div', { class: 'small' }, userLink(list === 'badges' ? x.uid : list === 'pharmacies' ? x.id : x.ownerUid, 'মালিকের সব তথ্য')) : null)));
  }

  // =========================================================
  // হাসপাতাল যোগের অনুরোধ
  // =========================================================
  function pageHospitals(r, alive) {
    const items = itemsOf('hospitalRequests');
    setTitle('হাসপাতাল যোগের অনুরোধ', `${bn(items.length)}টা অপেক্ষায়`);
    const run = (id, op, targetId) => () => act('admin_hospital_resolve', { id, op, ...(targetId ? { targetId } : {}) });
    paint(alive,
      h('p', { class: 'note', text: 'ব্যবহারকারীরা যে হাসপাতাল তালিকায় পাননি, সেগুলো যোগ করতে চেয়েছেন। আগে থেকে একই হাসপাতাল থাকলে "মেলান" চাপুন।' }),
      table(items, [
        { label: 'হাসপাতাল', main: true, cell: (x) => [x.nameEn || x.nameBn || '—', x.nameBn && x.nameEn ? h('div', { class: 'small', text: x.nameBn }) : null,
          x.count > 1 ? h('div', { class: 'chips' }, h('span', { class: 'chip pri', text: `${bn(x.count)} জন চেয়েছেন` })) : null] },
        { label: 'ধরন', cell: (x) => L.hospitalType[x.type] || x.type || '—' },
        { label: 'এলাকা', cell: (x) => [x.areaLabel || x.district || '—', x.address ? h('div', { class: 'small', text: x.address }) : null, x.phone ? h('div', { class: 'small', text: bn(x.phone) }) : null] },
        { label: 'একই রকম নাম', cell: (x) => ((x.possibleDuplicates || []).length
          ? (x.possibleDuplicates || []).map((d) => h('div', { class: 'small' }, [d.nameBn || d.nameEn, d.areaLabel].filter(Boolean).join(', '), ' ',
            actionBtn('মেলান', 'quiet', run(x.id, 'merge', d.id), 'আগের হাসপাতালের সাথে মেলানো হয়েছে', afterChange)))
          : '—') },
      ], (x) => [actionBtn('অনুমোদন', 'primary', run(x.id, 'approve'), 'হাসপাতাল তালিকায় যোগ হয়েছে', afterChange), actionBtn('বাতিল', 'warn', run(x.id, 'reject'), 'অনুরোধ বাতিল করা হয়েছে', afterChange)]));
  }

  // =========================================================
  // রিপোর্ট
  // =========================================================
  function pageReports(r, alive) {
    const items = itemsOf('reports');
    setTitle('রিপোর্ট', `${bn(items.length)}টা খোলা`);
    paint(alive, table(items, [
      { label: 'কী', main: true, cell: (x) => [x.kind === 'homevisit' ? (x.reportedRole === 'patient' ? 'হোম ভিজিট (আগের): রোগী' : 'হোম ভিজিট (আগের): নার্স') : L.report[x.kind] || x.kind,
        h('div', { class: 'chips' }, h('span', { class: 'chip bad', text: `${bn(x.count)}টি রিপোর্ট` }))] },
      { label: 'কার', cell: (x) => [x.authorName || '—', x.reportedUid || x.kind === 'homecare' || x.kind === 'provider' ? h('div', { class: 'small' }, userLink(x.reportedUid || x.targetPath.split('/')[1])) : null] },
      { label: 'লেখা', cell: (x) => [h('div', { class: 'body', text: x.text || '' }),
        x.snapshot ? h('div', { class: 'small' }, h('span', { class: 'chip bad', text: 'রিপোর্টের পর বদলেছে' }), ' রিপোর্টের সময়ের লেখা: ', x.snapshot) : null,
        ['question', 'answer', 'tip'].includes(x.kind) && (x.reasons || []).length ? h('div', { class: 'small', text: `কারণ: ${x.reasons.join('; ')}` }) : null] },
    ], (x) => {
      const body = { targetPath: x.targetPath, kind: x.kind, ...(x.reportedUid ? { reportedUid: x.reportedUid } : {}) };
      const listing = x.kind === 'ambulance' || x.kind === 'pharmacy' || x.kind === 'bloodbank';
      const reason = listing ? reasonBox() : null;
      const removeLabel = x.kind === 'homevisit' || x.kind === 'homecare' ? 'বাসায় সেবা বন্ধ' : x.kind === 'provider' ? 'তালিকা বন্ধ' : x.kind === 'ambulance' || x.kind === 'pharmacy' || x.kind === 'bloodbank' ? 'বন্ধ করুন' : 'পোস্ট মুছুন';
      return [
        reason,
        actionBtn(removeLabel, 'warn', () => act('admin_report_resolve', { ...body, remove: true, ...(reason && reason.value.trim() ? { reason: reason.value.trim() } : {}) }), 'ব্যবস্থা নেওয়া হয়েছে', afterChange),
        actionBtn('রেখে দিন', 'quiet', () => act('admin_report_resolve', { ...body, remove: false }), 'রেখে দেওয়া হয়েছে', afterChange),
      ];
    }));
  }

  // =========================================================
  // তারিখ সংশোধন
  // =========================================================
  function pageCorrections(r, alive) {
    const items = itemsOf('corrections');
    setTitle('রক্তদানের তারিখ সংশোধন', `${bn(items.length)}টা অপেক্ষায়`);
    const run = (id, approve) => () => post('/api/admin/resolve', { id, approve });
    paint(alive, table(items, [
      { label: 'নাম', main: true, cell: (c) => [c.name || '—', h('div', { class: 'small' }, userLink(c.uid))] },
      { label: 'এখনকার তারিখ', cell: (c) => fmtDate(c.currentMillis) },
      { label: 'চাওয়া তারিখ', cell: (c) => (c.requestedMillis ? fmtDate(c.requestedMillis) : 'কখনো দেননি') },
      { label: 'কারণ', cell: (c) => h('div', { class: 'body', text: c.reason || '—' }) },
    ], (c) => [actionBtn('অনুমোদন', 'primary', run(c.id, true), 'তারিখ সংশোধন করা হয়েছে', afterChange), actionBtn('বাতিল', 'warn', run(c.id, false), 'অনুরোধ বাতিল করা হয়েছে', afterChange)]));
  }

  // =========================================================
  // আপিল ও সমস্যা
  // =========================================================
  async function pageTickets(r, alive) {
    const tabs = [['open', 'উত্তরের অপেক্ষায়'], ['answered', 'উত্তর দেওয়া'], ['closed', 'বন্ধ']];
    const st = tabs.some(([k]) => k === r.params.get('s')) ? r.params.get('s') : 'open';
    setTitle('আপিল ও সমস্যা', L.ticket[st]);
    const items = await getList('tickets', st);
    let q = '';
    const box = h('div');
    const countEl = h('span', { class: 'count' });
    const draw = () => {
      const shown = items.filter((x) => matches(q, x.name, x.email, L.category[x.category], ...(x.messages || []).map((m) => m.text)));
      countEl.textContent = `${bn(shown.length)}টা`;
      box.replaceChildren(table(shown, [
        { label: 'কে', main: true, cell: (x) => [x.name || '—', h('div', { class: 'small', text: x.email || '' })] },
        { label: 'বিষয়', cell: (x) => h('span', { class: 'chip pri', text: L.category[x.category] || x.category }) },
        { label: 'শেষ বার্তা', cell: (x) => {
          const m = (x.messages || [])[x.messages.length - 1];
          return m ? [h('div', { class: 'body', text: m.text.length > 140 ? `${m.text.slice(0, 140)}…` : m.text }), h('div', { class: 'small', text: `${m.from === 'admin' ? 'আমরা' : 'ব্যবহারকারী'}, ${ago(m.at)}` })] : '—';
        } },
        { label: 'বার্তা', cell: (x) => bn((x.messages || []).length) },
      ], null, (x) => go(`#/ticket/${encodeURIComponent(x.id)}`)));
    };
    const tb = toolbar({ tabs, current: st, base: 'tickets', search: 'নাম, ইমেইল বা লেখা', onSearch: (v) => { q = v; draw(); } });
    tb.append(countEl);
    draw();
    paint(alive, tb, h('p', { class: 'note', text: 'অ্যাপের "সাহায্য ও আপিল" থেকে আসে। খোলা আপিলে যিনি বেশিক্ষণ অপেক্ষা করছেন, তিনি আগে।' }), box);
  }

  async function pageTicket(r, alive) {
    setTitle('আপিল', '');
    const res = await act('admin_ticket', { id: r.arg });
    if (alive && !alive()) return;
    const t = res.item;
    setTitle(`আপিল: ${L.category[t.category] || t.category}`, L.ticket[t.status]);
    const reply = h('textarea', { class: 'reply', placeholder: 'উত্তর লিখুন। ব্যবহারকারী অ্যাপে নোটিফিকেশন পাবেন।', 'aria-label': 'উত্তর', maxlength: 1500 });
    const send = (close) => async () => {
      const text = reply.value.trim();
      if (!text && !close) throw new Error('উত্তর লিখুন');
      await act('admin_ticket_reply', { id: t.id, text, close });
    };
    const doneTo = async () => {
      delete S.lists['tickets:answered'];
      delete S.lists['tickets:closed'];
      await afterChange();
    };
    paint(alive,
      h('div', { class: 'toolbar' }, h('a', { href: '#/tickets', text: 'সব আপিল' })),
      h('div', { class: 'card pad' },
        h('div', { class: 'profile' },
          h('div', null, h('h2', { text: t.name || '—' }), h('div', { class: 'note', text: t.email || '' }), h('div', { class: 'small' }, userLink(t.uid, 'এই ব্যবহারকারীর সব কিছু দেখুন ও ব্যবস্থা নিন'))),
          h('div', null, h('span', { class: `chip ${t.status === 'closed' ? '' : t.status === 'open' ? 'bad' : 'good'}`, text: L.ticket[t.status] }), h('div', { class: 'small', text: `খোলা হয়েছে ${fmtDT(t.createdAt)}` }))),
        h('div', { class: 'convo' }, (t.messages || []).map((m) => h('div', { class: `msg ${m.from === 'admin' ? 'admin' : 'user'}` },
          m.text, h('span', { class: 'when', text: `${m.from === 'admin' ? 'Lifolo টিম' : t.name || 'ব্যবহারকারী'}, ${fmtDT(m.at)}` })))),
        reply,
        h('div', { class: 'reply-actions' },
          actionBtn('উত্তর পাঠান', 'primary', send(false), 'উত্তর পাঠানো হয়েছে', doneTo),
          actionBtn('উত্তর দিয়ে বন্ধ করুন', 'tonal', send(true), 'উত্তর পাঠিয়ে বন্ধ করা হয়েছে', doneTo),
          t.status !== 'closed' ? actionBtn('উত্তর ছাড়া বন্ধ করুন', 'warn', () => act('admin_ticket_reply', { id: t.id, close: true }), 'বন্ধ করা হয়েছে', doneTo) : null)));
  }

  // =========================================================
  // ব্যবহারকারী
  // =========================================================
  async function pageUsers(r, alive) {
    setTitle('ব্যবহারকারী', 'খুঁজুন ও ব্যবস্থা নিন');
    const q0 = r.params.get('q') || '';
    const input = h('input', { type: 'search', value: q0, placeholder: 'ইমেইল, ফোন নম্বর বা ইউজার আইডি', 'aria-label': 'ব্যবহারকারী খুঁজুন' });
    const box = h('div');
    const form = h('form', { class: 'field-row', onsubmit: (e) => { e.preventDefault(); go(`#/users?q=${encodeURIComponent(input.value.trim())}`); } },
      input, h('button', { class: 'btn primary', type: 'submit' }, icon('search'), 'খুঁজুন'));
    paint(alive, h('div', { class: 'card pad' }, form,
      h('p', { class: 'note search-hint', text: 'ফোন নম্বর দিলে প্রোফাইল, ফার্মেসি, অ্যাম্বুলেন্স আর ব্লাড ব্যাংকের নম্বরেও খোঁজা হয়।' })), h('div', { class: 'section-title' }), box);
    if (!q0) { input.focus(); return; }
    box.replaceChildren(h('p', { class: 'note', text: 'খোঁজা হচ্ছে…' }));
    try {
      const res = await act('admin_user_find', { q: q0 });
      if (alive && !alive()) return;
      box.replaceChildren(table(res.items || [], [
        { label: 'নাম', main: true, cell: (u) => [u.name || '—', u.disabled ? [' ', h('span', { class: 'chip bad', text: 'অ্যাকাউন্ট বন্ধ' })] : null] },
        { label: 'ইমেইল', cell: (u) => u.email || '—' },
        { label: 'ফোন', cell: (u) => bn(u.phone) || '—' },
        { label: 'রক্তের গ্রুপ', cell: (u) => u.bloodGroup || '—' },
        { label: 'এলাকা', cell: (u) => u.district || '—' },
      ], null, (u) => go(`#/user/${encodeURIComponent(u.uid)}`)));
    } catch (e) {
      box.replaceChildren(h('p', { class: 'error', role: 'alert', text: e.message }));
    }
  }

  async function pageUser(r, alive) {
    setTitle('ব্যবহারকারী', '');
    const res = await act('admin_user', { uid: r.arg });
    if (alive && !alive()) return;
    const u = res.user;
    setTitle(u.name || u.email || 'ব্যবহারকারী', u.disabled ? 'অ্যাকাউন্ট বন্ধ' : '');
    const reload = async () => { await afterChange(); };
    const uact = (op) => () => act('admin_user_action', { uid: u.uid, op });

    const ctl = (title, desc, chip, actions) => h('div', { class: 'ctl' },
      h('div', { class: 'what' }, h('b', null, title, chip ? [' ', chip] : null), desc ? h('div', { class: 'note', text: desc }) : null),
      h('div', { class: 'actions' }, actions));

    // বাসায় সেবা: শুধু ডাক্তার/নার্সের, অথবা আগের নিষেধাজ্ঞা থাকলে
    const homeCareCtl = () => {
      const blocked = u.blocks.homeVisitBanned || u.blocks.homeVisitBlocked;
      const pro = u.badge && ['doctor', 'nurse'].includes(u.badge.type);
      const ps = u.provider || {};
      // রিপোর্টে পুরো তালিকা বন্ধ থাকলে সেটা আগে (বাসায় সেবার ব্লক থাকলেও)
      if (ps.suspended) {
        return ctl('ডাক্তার/নার্সের তালিকা', ps.suspendReason === 'report' ? 'রিপোর্টের পর তালিকা (চেম্বার আর বাসায় সেবা) বন্ধ।' : 'অ্যাকাউন্ট বন্ধের সাথে তালিকাও বন্ধ।',
          h('span', { class: 'chip bad', text: 'বন্ধ' }),
          ps.suspendReason === 'report' ? [actionBtn('তালিকা আবার চালু', 'tonal', uact('provider_unsuspend'), 'তালিকা আবার চালু হয়েছে', reload)] : []);
      }
      if (!blocked && !pro) return null;
      if (blocked) {
        return ctl('বাসায় সেবা', u.blocks.homeVisitBlocked ? 'রিপোর্টের পর বন্ধ: তালিকায় দেখান না, নিজে আবার চালু করতে পারবেন না।' : 'আগের হোম ভিজিটে রোগী হিসেবে নিষেধাজ্ঞা ছিল (এখন অনুরোধ-ব্যবস্থা নেই)।',
          h('span', { class: 'chip bad', text: 'বন্ধ' }),
          [actionBtn('নিষেধাজ্ঞা তুলুন', 'tonal', uact('homevisit_unban'), 'নিষেধাজ্ঞা তোলা হয়েছে। ডাক্তার/নার্স অ্যাপ থেকে নিজে আবার চালু করবেন।', reload)]);
      }
      const p = u.provider || {};
      return ctl('বাসায় সেবা',
        p.homeVisit ? `তালিকায় আছেন। রোগীরা ফোন করেন ${bn(p.homePhone) || '—'} নম্বরে; অ্যাপ থেকে ফোন এসেছে ${bn(p.homeCalls || 0)} বার।` : 'চালু করেননি।',
        p.homeVisit ? h('span', { class: 'chip good', text: 'চালু' }) : null,
        p.homeVisit ? [actionBtn('বাসায় সেবা বন্ধ করুন', 'warn', uact('homecare_ban'), 'বাসায় সেবা বন্ধ করা হয়েছে', reload)] : []);
    };

    const controls = [
      ctl('অ্যাকাউন্ট', u.disabled ? 'বন্ধ: লগইন করতে পারবেন না।' : 'চালু।',
        h('span', { class: `chip ${u.disabled ? 'bad' : 'good'}`, text: u.disabled ? 'বন্ধ' : 'চালু' }),
        u.isOwner ? [h('span', { class: 'note', text: 'মালিকের অ্যাকাউন্ট' })]
          : u.disabled ? [actionBtn('অ্যাকাউন্ট খুলে দিন', 'tonal', uact('enable'), 'অ্যাকাউন্ট খুলে দেওয়া হয়েছে', reload)]
            : (() => {
              // কারণ ব্যবহারকারী লগইনের চেষ্টায় দেখেন
              const reason = reasonBox();
              return [reason, actionBtn('অ্যাকাউন্ট বন্ধ করুন', 'warn', () => act('admin_user_action', { uid: u.uid, op: 'disable', reason: reason.value.trim() }), 'অ্যাকাউন্ট বন্ধ করা হয়েছে', reload)];
            })()),
      homeCareCtl(),
      ctl('ওষুধের অর্ডার',
        u.blocks.medBlocked ? `অর্ডার নিয়ে না যাওয়ায় (${bn(u.blocks.medNoShow)} বার) অর্ডার দেওয়া বন্ধ আছে।` : u.blocks.medNoShow ? `অর্ডার নিয়ে যাননি ${bn(u.blocks.medNoShow)} বার।` : 'কোনো ব্লক নেই।',
        u.blocks.medBlocked ? h('span', { class: 'chip bad', text: 'বন্ধ' }) : null,
        u.blocks.medBlocked || u.blocks.medNoShow ? [actionBtn('ব্লক তুলুন', 'tonal', uact('med_unblock'), 'ওষুধের অর্ডারের ব্লক তোলা হয়েছে', reload)] : []),
    ];
    if (u.badge) {
      const run = (approve) => () => act('admin_badge_resolve', { uid: u.uid, approve });
      controls.push(ctl(`ব্যাজ: ${L.badge[u.badge.type] || u.badge.type}`, `রেজি. নম্বর ${u.badge.regNo || '—'}${u.badge.workplace ? `, ${u.badge.workplace}` : ''}`,
        listingChip(u.badge.status),
        u.badge.status === 'approved' ? [actionBtn('ব্যাজ বাতিল', 'warn', run(false), 'ব্যাজ বাতিল করা হয়েছে', reload)]
          : [actionBtn('অনুমোদন দিন', 'tonal', run(true), 'ব্যাজ অনুমোদন করা হয়েছে', reload), u.badge.status === 'pending' ? actionBtn('বাতিল', 'warn', run(false), 'আবেদন বাতিল করা হয়েছে', reload) : null]));
    }
    if (u.pharmacy) {
      controls.push(ctl(`ফার্মেসি: ${u.pharmacy.name}`, [bn(u.pharmacy.phone), u.pharmacy.district].filter(Boolean).join(', '), listingChip(u.pharmacy.status),
        statusActions('admin_pharmacy_resolve', u.pharmacy.id, u.pharmacy.status, 'ফার্মেসি').map((b) => b)));
    }
    (u.bloodBanks || []).forEach((b) => controls.push(ctl(`ব্লাড ব্যাংক: ${b.name}`, [bn(b.phone), b.district].filter(Boolean).join(', '), listingChip(b.status),
      statusActions('admin_bloodbank_resolve', b.id, b.status, 'ব্লাড ব্যাংক'))));
    (u.ambulances || []).forEach((a) => controls.push(ctl(`অ্যাম্বুলেন্স: ${a.name}`, [a.vehicleNo, bn(a.phone)].filter(Boolean).join(', '), listingChip(a.status),
      statusActions('admin_ambulance_resolve', a.id, a.status, 'অ্যাম্বুলেন্স'))));
    if (u.provider) {
      controls.push(ctl('ডাক্তার/নার্সের তালিকা', `${u.provider.listed ? 'তালিকায় দেখা যায়' : 'তালিকায় নেই'}${u.provider.chambers ? `, ${bn(u.provider.chambers)}টা চেম্বার` : ', চেম্বার নেই'}${u.provider.homeVisit ? ', বাসায় যান' : ''}`, null, []));
    }

    const facts = [
      ['ইমেইল', u.email || '—'], ['ফোন', `${bn(u.phone) || '—'}${u.phoneVerified ? ' (যাচাই করা)' : ''}`],
      ['রক্তের গ্রুপ', u.bloodGroup || '—'], ['এলাকা', u.district || '—'],
      ['শেষ রক্তদান', u.lastDonationMillis ? fmtDate(u.lastDonationMillis) : 'দেননি / জানা নেই'],
      ['যোগ দিয়েছেন', fmtDate(u.createdAt)], ['শেষ সক্রিয়', ago(u.lastSeenAt)],
      ['তার বিরুদ্ধে রিপোর্ট', bn(u.activity.reportsAgainst)], ['ইউজার আইডি', u.uid],
    ];

    const A = u.activity;
    const actTable = (title, items, cols) => [h('h2', { class: 'section-title', text: `${title} (${bn(items.length)})` }), table(items, cols)];
    paint(alive,
      h('div', { class: 'card pad' },
        h('div', { class: 'profile' },
          h('div', null, h('h2', { text: u.name || '—' }),
            h('div', { class: 'chips' }, u.isOwner ? h('span', { class: 'chip pri', text: 'মালিক' }) : u.isAdmin ? h('span', { class: 'chip pri', text: 'অ্যাডমিন' }) : null,
              u.badge && u.badge.status === 'approved' ? h('span', { class: 'chip good', text: L.badge[u.badge.type] }) : null)),
          h('div', null, h('a', { href: `#/users?q=${encodeURIComponent(u.email || u.uid)}`, text: 'খোঁজায় ফিরুন' }))),
        h('div', { class: 'facts' }, facts.map(([k, v]) => h('div', null, h('span', { text: k }), v)))),
      h('h2', { class: 'section-title', text: 'ব্যবস্থা' }),
      h('div', { class: 'card pad' }, controls),
      (u.donationDateLog || []).length ? actTable('শেষ রক্তদানের তারিখ বদল', u.donationDateLog, [
        { label: 'কখন', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'আগে', cell: (x) => (x.from ? fmtDate(x.from) : 'কখনো দেননি') },
        { label: 'পরে', cell: (x) => (x.to ? fmtDate(x.to) : 'কখনো দেননি') },
        { label: 'সতর্কতা', cell: (x) => (x.risk === 'backward' ? h('span', { class: 'chip bad', text: 'পেছানো' }) : x.risk === 'short_gap' ? h('span', { class: 'chip bad', text: '১২০ দিনের কম ফাঁক' }) : '—') },
      ]) : null,
      // আগের ব্যবস্থা (ওষুধের অর্ডার, হোম ভিজিট) — থাকলে
      A.orders.length ? actTable('ওষুধের অর্ডার (আগের ব্যবস্থা)', A.orders, [
        { label: 'তারিখ', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'ফার্মেসি', cell: (x) => x.shop || '—' },
        { label: 'ওষুধ', cell: (x) => `${bn(x.items)}টা` },
        { label: 'অবস্থা', cell: (x) => L.order[x.status] || x.status },
      ]) : null,
      A.visits.length ? actTable('হোম ভিজিট (আগের ব্যবস্থা)', A.visits, [
        { label: 'তারিখ', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'ভূমিকা', cell: (x) => (x.role === 'nurse' ? 'নার্স' : 'রোগী') },
        { label: 'সেবা', cell: (x) => (x.services || []).join(', ') || '—' },
        { label: 'অবস্থা', cell: (x) => L.visit[x.status] || x.status },
      ]) : null,
      h('h2', { class: 'section-title', text: 'অ্যাপে যা যা করেছেন' }),
      userTabs(u));
  }

  // =========================================================
  // ব্যবহারকারীর পূর্ণ দৃশ্য: অ্যাপের ভাগ ধরে (শুধু দেখা)
  // =========================================================
  const RESP = { accepted: 'রাজি', donated: 'রক্ত দিয়েছেন', cancelled: 'বাতিল করেছেন', declined: 'বাদ পড়েছেন' };
  const EMER = { notified: 'জানানো হয়েছিল', coming: 'আসছি বলেছেন', cancelled: 'আসতে পারেননি', donated: 'রক্ত দিয়েছেন', not_donated: 'দেননি' };
  const CALLKIND = { ambulance: 'অ্যাম্বুলেন্স', pharmacy: 'ফার্মেসি', bloodbank: 'ব্লাড ব্যাংক', homecare: 'বাসায় সেবা' };
  const USER_TABS = [['blood', 'রক্ত'], ['community', 'আলোচনা'], ['services', 'সেবা'], ['care', 'ডাক্তার ও অ্যাপয়েন্টমেন্ট'],
    ['inbox', 'নোটিফিকেশন'], ['support', 'আপিল'], ['admin', 'অ্যাডমিন ব্যবস্থা'], ['timeline', 'সময়ক্রম']];

  function userTabs(u) {
    const cache = {};
    const box = h('div', { class: 'user-tab' });
    const bar = h('div', { class: 'seg tabs-wrap', role: 'tablist' });
    const load = async (sec) => {
      if (!cache[sec]) cache[sec] = act('admin_user_section', { uid: u.uid, section: sec });
      return cache[sec];
    };
    const sub = (title, n) => h('h3', { class: 'sub-title', text: n == null ? title : `${title} (${bn(n)})` });
    const note = (t) => h('p', { class: 'note', text: t });
    const idxNote = (v) => (v === 'index' ? note('এই অংশ দেখাতে Firestore-এ একটা সূচি (index) চালু করতে হবে — Lifolo টিমকে জানান।') : v ? note(v) : null);

    const render = {
      blood: (d) => [
        d.profile ? h('div', { class: 'facts' }, [
          ['রক্তের গ্রুপ', d.profile.bloodGroup === 'unknown' ? 'জানা নেই' : d.profile.bloodGroup || '—'],
          ['শেষ রক্তদান', d.profile.lastDonationMillis ? fmtDate(d.profile.lastDonationMillis) : '—'],
          ['এলাকা', d.profile.placeName || '—'], ['নোটিফিকেশন', d.profile.notify ? 'চালু' : 'বন্ধ'],
          ['ফোন (ডিভাইস)', `${bn(d.profile.devices)}টা`], ['এখন রাজি আছেন', `${bn(d.profile.openCommitments)}টা রিকোয়েস্টে`],
        ].map(([k, v]) => h('div', null, h('span', { text: k }), v))) : null,
        sub('তাঁর দেওয়া রিকোয়েস্ট', d.requests.length),
        table(d.requests, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'রক্ত', cell: (x) => `${x.group || '—'} · ${bn(x.bags)} ব্যাগ` },
          { label: 'হাসপাতাল', cell: (x) => [x.hospital || '—', x.place ? h('div', { class: 'small', text: x.place }) : null] },
          { label: 'সাড়া', cell: (x) => `রাজি ${bn(x.accepted)} · নিশ্চিত ${bn(x.confirmed)}` },
          { label: 'অবস্থা', cell: (x) => [L.request[x.status] || x.status, x.closedBy ? h('div', { class: 'small', text: { requester: 'নিজে বন্ধ', not_needed: 'আর লাগবে না', expired: 'সময় শেষ' }[x.closedBy] || x.closedBy }) : null] },
        ]),
        sub('যেসব রিকোয়েস্টে সাড়া দিয়েছেন', d.responses.length), idxNote(d.responsesNote),
        table(d.responses, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'রিকোয়েস্ট', cell: (x) => [`${x.group || '—'} · ${x.hospital || '—'}`, x.requesterUid ? h('div', { class: 'small' }, userLink(x.requesterUid, 'রক্তপ্রার্থী')) : null] },
          { label: 'অবস্থা', cell: (x) => (RESP[x.status] || x.status) + (x.confirmed ? ' · নিশ্চিত' : '') },
        ]),
        sub('জরুরি আহ্বান', d.emergencies.length), idxNote(d.emergenciesNote),
        table(d.emergencies, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'হাসপাতাল', cell: (x) => x.hospital || '—' },
          { label: 'সাড়া', cell: (x) => EMER[x.status] || x.status },
        ]),
        d.corrections.length ? [sub('রক্তদানের তারিখ সংশোধনের অনুরোধ', d.corrections.length), table(d.corrections, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'চাওয়া তারিখ', cell: (x) => (x.requestedMillis ? fmtDate(x.requestedMillis) : 'কখনো দেননি') },
          { label: 'কারণ', cell: (x) => x.reason || '—' },
          { label: 'অবস্থা', cell: (x) => x.status },
        ])] : null,
      ],
      community: (d) => [
        sub('প্রশ্ন', d.questions.length),
        table(d.questions, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'প্রশ্ন', cell: (x) => [h('div', { class: 'body', text: x.text }), h('div', { class: 'chips' },
            x.anonymous ? h('span', { class: 'chip', text: 'নাম লুকানো' }) : null, x.edited ? h('span', { class: 'chip', text: 'সম্পাদিত' }) : null)] },
          { label: 'উত্তর', cell: (x) => bn(x.answers) },
        ]),
        sub('উত্তর', d.answers.length), idxNote(d.answersNote),
        table(d.answers, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'উত্তর', cell: (x) => h('div', { class: 'body', text: x.text }) },
        ]),
        sub('টিপস', d.tips.length),
        table(d.tips, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'টিপস', cell: (x) => [h('b', { text: x.title }), h('div', { class: 'small', text: x.body })] },
        ]),
        sub('যা রিপোর্ট করেছেন', d.reportsMade.length),
        table(d.reportsMade, [
          { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'কী', cell: (x) => L.report[x.kind] || x.kind },
          { label: 'কারণ', cell: (x) => x.reason || '—' },
          { label: 'অবস্থা', cell: (x) => ({ open: 'খোলা', removed: 'ব্যবস্থা নেওয়া হয়েছে', dismissed: 'রেখে দেওয়া' }[x.status] || x.status) },
        ]),
      ],
      services: (d) => {
        const lst = (title, items, extra) => [sub(title, items.length), table(items, [
          { label: 'নাম', main: true, cell: (x) => [x.name || '—', x.verified ? h('div', { class: 'chips' }, h('span', { class: 'chip good', text: 'কাগজ যাচাই করা' })) : null] },
          { label: 'তথ্য', cell: (x) => [extra(x), h('div', { class: 'small', text: [bn(x.phone), x.district].filter(Boolean).join(' · ') })] },
          { label: 'অবস্থা', cell: (x) => [L.listing[x.status] || x.status, x.statusReason ? h('div', { class: 'small', text: `কারণ: ${x.statusReason}` }) : null] },
          { label: 'রেটিং', cell: (x) => (x.rating ? bn(x.rating) : '—') },
        ])];
        return [
          lst('তাঁর অ্যাম্বুলেন্স', d.ambulances, (x) => x.vehicleNo || ''),
          lst('তাঁর ফার্মেসি', d.pharmacy ? [d.pharmacy] : [], (x) => `লাইসেন্স ${x.licenseNo || '—'}`),
          lst('তাঁর ব্লাড ব্যাংক', d.bloodBanks, (x) => L.bankKind[x.kind] || x.kind || ''),
          sub('যেখানে ফোন করেছেন', d.calls.length),
          table(d.calls, [
            { label: 'শেষ ফোন', main: true, cell: (x) => fmtDT(x.at) },
            { label: 'কোথায়', cell: (x) => [x.name, h('div', { class: 'small', text: CALLKIND[x.kind] || x.kind })] },
            { label: 'রেটিং', cell: (x) => (x.rating ? [`${bn(x.rating)} ★`, x.comment ? h('div', { class: 'small', text: x.comment }) : null] : '—') },
          ]),
          sub('ওষুধ খোঁজা', d.medFinds.length),
          table(d.medFinds, [
            { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
            { label: 'ওষুধ', cell: (x) => [x.drug, h('div', { class: 'small', text: x.district })] },
            { label: 'উত্তর', cell: (x) => `${bn(x.asked)}টা দোকানে জিজ্ঞেস, ${bn(x.have)}টায় আছে` },
          ]),
        ];
      },
      care: (d) => [
        d.provider ? [sub('ডাক্তার/নার্সের তালিকা'), h('div', { class: 'facts' }, [
          ['পেশা', L.badge[d.provider.type] || d.provider.type || '—'], ['তালিকায়', d.provider.suspended ? 'বন্ধ' : d.provider.listed ? 'দেখা যায়' : 'নেই'],
          ['বাসায় সেবা', d.provider.homeVisit ? `চালু · ${bn(d.provider.homePhone)}` : 'বন্ধ'], ['বাসায় সেবায় ফোন এসেছে', `${bn(d.homeCallsReceived)} জনের`],
        ].map(([k, v]) => h('div', null, h('span', { text: k }), v))),
        table(d.provider.chambers, [
          { label: 'চেম্বার', main: true, cell: (x) => x.hospital || '—' },
          { label: 'বার', cell: (x) => (x.days || []).map((n) => ['রবি', 'সোম', 'মঙ্গল', 'বুধ', 'বৃহস্পতি', 'শুক্র', 'শনি'][n]).join(', ') || '—' },
          { label: 'সময়', cell: (x) => (x.start ? `${bn(x.start)}–${bn(x.end)}` : '—') },
          { label: 'অ্যাপয়েন্টমেন্ট', cell: (x) => (x.appointments ? 'নেন' : 'নেন না') },
        ])] : note('ডাক্তার/নার্সের তালিকায় নেই।'),
        sub('অ্যাপয়েন্টমেন্ট', d.appointments.length),
        table(d.appointments, [
          { label: 'অনুরোধ', main: true, cell: (x) => fmtDT(x.at) },
          { label: 'ভূমিকা', cell: (x) => (x.role === 'provider' ? 'ডাক্তার/নার্স হিসেবে' : 'রোগী হিসেবে') },
          { label: 'কার সাথে', cell: (x) => [x.other || '—', h('div', { class: 'small', text: x.chamber })] },
          { label: 'দিন', cell: (x) => [fmtDate(x.date), x.serial ? h('div', { class: 'small', text: `সিরিয়াল ${bn(x.serial)}` }) : null] },
          { label: 'অবস্থা', cell: (x) => L.appt[x.status] || x.status },
        ]),
      ],
      inbox: (d) => [sub('গত ৩০ দিনের নোটিফিকেশন', d.items.length), table(d.items, [
        { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'নোটিফিকেশন', cell: (x) => [h('b', { text: x.title }), x.body ? h('div', { class: 'small', text: x.body }) : null] },
      ])],
      support: (d) => [sub('আপিল ও সমস্যা', d.items.length), table(d.items, [
        { label: 'শেষ হালনাগাদ', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'বিষয়', cell: (x) => [h('a', { href: `#/ticket/${encodeURIComponent(x.id)}`, text: L.category[x.category] || x.category }), h('div', { class: 'small', text: x.subject })] },
        { label: 'অবস্থা', cell: (x) => L.ticket[x.status] || x.status },
      ])],
      admin: (d) => {
        // শুধু দেখা (পাতা খোলা) আলাদা, আসল ব্যবস্থা আগে
        const acts = d.items.filter((x) => x.action !== 'admin_user_section');
        const views = d.items.filter((x) => x.action === 'admin_user_section');
        return [sub('এই ব্যবহারকারীর ওপর অ্যাডমিনদের ব্যবস্থা', acts.length), logTable(acts),
          sub('কে কবে এই পাতা দেখেছেন', views.length), logTable(views)];
      },
    };

    // সব ভাগ এক তালিকায়, নতুন আগে
    async function timeline() {
      const secs = ['blood', 'community', 'services', 'care', 'support'];
      const all = await Promise.all(secs.map(load));
      const [b, c, sv, ca, sp] = all;
      const ev = [
        ...b.requests.map((x) => ({ at: x.at, area: 'রক্ত', text: `রিকোয়েস্ট দিয়েছেন: ${x.group} · ${x.hospital}` })),
        ...b.responses.map((x) => ({ at: x.at, area: 'রক্ত', text: `সাড়া (${RESP[x.status] || x.status}): ${x.group} · ${x.hospital}` })),
        ...b.emergencies.map((x) => ({ at: x.at, area: 'জরুরি', text: `${EMER[x.status] || x.status}: ${x.hospital}` })),
        ...c.questions.map((x) => ({ at: x.at, area: 'আলোচনা', text: `প্রশ্ন: ${x.text.slice(0, 80)}` })),
        ...c.answers.map((x) => ({ at: x.at, area: 'আলোচনা', text: `উত্তর: ${x.text.slice(0, 80)}` })),
        ...c.tips.map((x) => ({ at: x.at, area: 'আলোচনা', text: `টিপস: ${x.title}` })),
        ...c.reportsMade.map((x) => ({ at: x.at, area: 'রিপোর্ট', text: `রিপোর্ট করেছেন: ${L.report[x.kind] || x.kind}` })),
        ...sv.calls.map((x) => ({ at: x.at, area: 'সেবা', text: `ফোন করেছেন: ${x.name} (${CALLKIND[x.kind] || x.kind})${x.rating ? ` · ${x.rating}★` : ''}` })),
        ...sv.medFinds.map((x) => ({ at: x.at, area: 'সেবা', text: `ওষুধ খুঁজেছেন: ${x.drug}` })),
        ...ca.appointments.map((x) => ({ at: x.at, area: 'ডাক্তার', text: `অ্যাপয়েন্টমেন্ট (${x.role === 'provider' ? 'ডাক্তার হিসেবে' : 'রোগী হিসেবে'}): ${x.other}` })),
        ...sp.items.map((x) => ({ at: x.at, area: 'আপিল', text: L.category[x.category] || x.category })),
      ].filter((e) => e.at).sort((a, z) => z.at - a.at).slice(0, 200);
      return [sub('সব কাজ, নতুন আগে', ev.length), idxNote(b.responsesNote || c.answersNote), table(ev, [
        { label: 'কবে', main: true, cell: (x) => [fmtDT(x.at), h('div', { class: 'small', text: ago(x.at) })] },
        { label: 'ভাগ', cell: (x) => h('span', { class: 'chip', text: x.area }) },
        { label: 'কী', cell: (x) => x.text },
      ])];
    }

    let current = '';
    async function show(sec) {
      current = sec;
      [...bar.children].forEach((a) => a.classList.toggle('on', a.dataset.sec === sec));
      box.replaceChildren(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));
      try {
        const nodes = sec === 'timeline' ? await timeline() : render[sec](await load(sec));
        if (current === sec) box.replaceChildren(...[nodes].flat(3).filter(Boolean));
      } catch (e) {
        delete cache[sec];
        if (current === sec) box.replaceChildren(h('p', { class: 'error', role: 'alert', text: e.message }));
      }
    }
    USER_TABS.forEach(([sec, label]) => bar.append(h('a', { href: '#', 'data-sec': sec, text: label, onclick: (e) => { e.preventDefault(); show(sec); } })));
    show('blood');
    return h('div', null, bar, h('div', { class: 'card pad' }, box));
  }

  // =========================================================
  // কাজের ইতিহাস
  // =========================================================
  function logTable(items) {
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

  async function pageLog(r, alive) {
    setTitle('কাজের ইতিহাস', 'কোন অ্যাডমিন কবে কী করেছেন');
    const res = await act('admin_log');
    if (alive && !alive()) return;
    let items = res.items || [];
    const box = h('div', null, logTable(items));
    const more = actionBtn('আরও আগের', 'quiet', async () => {
      const last = items[items.length - 1];
      const r2 = await act('admin_log', { before: last ? last.at : undefined });
      items = items.concat(r2.items || []);
      box.replaceChildren(logTable(items));
      if (!(r2.items || []).length) more.remove();
    }, null, () => { more.disabled = false; more.textContent = 'আরও আগের'; });
    paint(alive, box, items.length >= 100 ? h('div', { class: 'toolbar' }, more) : null);
  }

  // =========================================================
  // অ্যাডমিন (শুধু মালিক)
  // =========================================================
  function pageAdmins(r, alive) {
    setTitle('অ্যাডমিন', 'যোগ বা বাদ শুধু মালিক');
    const a = S.overview && S.overview.admins;
    if (!a) {
      paint(alive, h('p', { class: 'note', text: 'এই পাতা শুধু মালিক দেখতে পারেন।' }));
      return;
    }
    const input = h('input', { type: 'email', placeholder: 'নতুন অ্যাডমিনের ইমেইল', 'aria-label': 'নতুন অ্যাডমিনের ইমেইল', maxlength: 200 });
    const rows = [...(a.owners || []).map((email) => ({ email, owner: true })), ...(a.items || [])];
    paint(alive,
      h('p', { class: 'note', text: 'অ্যাডমিনরা সব আবেদন, আপিল আর রিপোর্ট দেখতে ও ব্যবস্থা নিতে পারেন। তাদের এই ইমেইল দিয়েই লগইন করতে হবে।' }),
      h('div', { class: 'section-title' }),
      table(rows, [
        { label: 'ইমেইল', main: true, cell: (x) => [x.email, x.owner ? [' ', h('span', { class: 'chip pri', text: 'মালিক' })] : null] },
      ], (x) => (x.owner ? [] : [actionBtn('বাদ দিন', 'warn', () => act('admin_remove', { email: x.email }), 'অ্যাডমিন থেকে বাদ দেওয়া হয়েছে', afterChange)])),
      h('h2', { class: 'section-title', text: 'নতুন অ্যাডমিন' }),
      h('div', { class: 'card pad' }, h('div', { class: 'field-row' }, input,
        actionBtn('যোগ করুন', 'primary', async () => {
          const email = input.value.trim().toLowerCase();
          if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Error('সঠিক ইমেইল দিন');
          await act('admin_add', { email });
        }, 'অ্যাডমিন যোগ হয়েছে', afterChange))));
  }

  // =========================================================
  // সময় ধরে কাজ
  // =========================================================
  ['click', 'keydown', 'pointermove', 'scroll', 'touchstart'].forEach((ev) =>
    window.addEventListener(ev, () => { S.lastActive = Date.now(); }, { passive: true }));

  // লগআউটের আগে সতর্কবার্তা: "চালু রাখুন" চাপলে আবার পুরো সময় পাওয়া যায়
  const warnBar = h('div', { class: 'idle-warn', role: 'alertdialog', 'aria-live': 'assertive', hidden: true },
    h('span', { text: 'অনেকক্ষণ কিছু করেননি। ২ মিনিটের মধ্যে নিজে থেকে লগআউট হবে।' }),
    h('button', { class: 'btn primary', type: 'button', onclick: () => {
      S.lastActive = Date.now();
      warnBar.hidden = true;
      loadOverview().then(drawNav).catch(() => {}); // সার্ভারের সময়ও নতুন করে
    }, text: 'চালু রাখুন' }));
  document.body.append(warnBar);

  setInterval(() => {
    if (!S.loggedIn) { warnBar.hidden = true; return; }
    const idle = Date.now() - S.lastActive;
    if (idle > IDLE_MS) {
      warnBar.hidden = true;
      logout('প্যানেলে ২ ঘণ্টা কিছু না করায় নিরাপত্তার জন্য লগআউট হয়েছে। আবার লগইন করুন।');
      return;
    }
    warnBar.hidden = idle < IDLE_MS - WARN_MS;
    // পেছনে চুপচাপ শুধু অপেক্ষার সংখ্যা হালনাগাদ (পাতা নতুন করে আঁকা হয় না, লেখা হারায় না)
    if (!document.hidden && !S.busy && Date.now() - S.overviewAt > AUTO_REFRESH_MS) {
      loadOverview().then(drawNav).catch(() => {});
    }
  }, 15000);

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && shell) shell.classList.remove('open');
  });

  start();
})();
