// লগইন: ইমেইলে কোড, তারপর কোড দিয়ে (কুকিতে সেশন)
import { defineComponent, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { h, icon } from './h';
import { S, post, start } from './state';
import { BN_DIGITS, EMAIL_RE, bn, errMsg, load, store } from './util';

export default defineComponent({
  name: 'AdminLogin',
  setup() {
    const stage = ref<'email' | 'code'>('email');
    const email = ref(load('lf_admin_email'));
    const error = ref(S.loginMessage || '');
    const sending = ref(false);
    const info = ref('');
    /** আগের প্যানেলে প্রতিবার পুরো ফর্ম নতুন করে আঁকা হতো (লেখা ঘর খালি হতো) — সেটাই */
    const drawN = ref(0);
    const form = ref<HTMLFormElement | null>(null);
    // আবার কোড চাওয়া যায় ১ মিনিট পর (সার্ভারের সীমার সাথে মিলিয়ে)
    const resendAt = ref(0);
    const now = ref(Date.now());
    let tick: ReturnType<typeof setInterval> | undefined;
    const waitSec = () => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000));
    function startTimer() {
      resendAt.value = Date.now() + 60 * 1000;
      now.value = Date.now();
      clearInterval(tick);
      tick = setInterval(() => {
        now.value = Date.now();
        if (!waitSec()) clearInterval(tick);
      }, 1000);
    }
    onUnmounted(() => clearInterval(tick));

    async function draw() {
      drawN.value++;
      await nextTick();
      const first = form.value?.querySelector('input');
      if (first) first.focus();
    }
    onMounted(draw);

    async function resend() {
      if (sending.value || waitSec() > 0) return;
      sending.value = true;
      error.value = '';
      info.value = '';
      draw();
      try {
        const r1 = await post('/api/auth/send-code', { email: email.value });
        if (r1 && r1.rid) S.rids = [...(S.rids || []), r1.rid].slice(-3);
        info.value = 'নতুন কোড গেছে। না পেলে Spam বা All Mail দেখুন।';
        startTimer();
      } catch (e) {
        error.value = errMsg(e);
      }
      sending.value = false;
      draw();
    }

    async function submit(ev: Event) {
      ev.preventDefault();
      if (sending.value) return;
      const input = (ev.target as HTMLFormElement).querySelector('input') as HTMLInputElement;
      error.value = '';
      if (stage.value === 'email') {
        email.value = input.value.trim().toLowerCase();
        if (!EMAIL_RE.test(email.value)) {
          error.value = 'সঠিক ইমেইল দিন';
          draw();
          return;
        }
        sending.value = true;
        draw();
        try {
          const r2 = await post('/api/auth/send-code', { email: email.value });
          S.rids = r2 && r2.rid ? [r2.rid] : [];
          store('lf_admin_email', email.value);
          stage.value = 'code';
          startTimer();
        } catch (e) {
          error.value = errMsg(e);
        }
        sending.value = false;
        draw();
      } else {
        const code = input.value.replace(/[০-৯]/g, (d) => String(BN_DIGITS.indexOf(d))).replace(/\D/g, '');
        if (code.length !== 6) {
          error.value = '৬ সংখ্যার কোড দিন';
          draw();
          return;
        }
        sending.value = true;
        draw();
        try {
          await post('/api/auth/verify-code', { email: email.value, code, web: true, ...(S.rids && S.rids.length ? { rids: S.rids } : {}) });
          S.lastActive = Date.now();
          await start();
          return;
        } catch (e) {
          error.value = errMsg(e);
        }
        sending.value = false;
        draw();
      }
    }

    return () => {
      const n = drawN.value;
      const w = waitSec();
      return h('div', { class: 'login-wrap' }, h('form', { ref: form, class: 'card login', novalidate: true, onsubmit: submit },
        h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), h('span', { text: 'অ্যাডমিন' })),
        h('h1', { text: 'লগইন' }),
        stage.value === 'code' ? h('p', { text: `কোড গেছে: ${email.value}` }) : null,
        error.value ? h('p', { class: 'error', role: 'alert', text: error.value }) : null,
        info.value && stage.value === 'code' ? h('p', { class: 'note', role: 'status', text: info.value }) : null,
        stage.value === 'email'
          ? [h('label', { class: 'field' }, h('span', { text: 'ইমেইল' }),
            h('input', { key: `e${n}`, type: 'email', name: 'email', autocomplete: 'email', required: true, value: email.value })),
          h('div', { class: 'row' }, h('button', { class: 'btn primary', type: 'submit', disabled: sending.value, text: sending.value ? 'পাঠানো হচ্ছে…' : 'কোড পাঠান' }))]
          : [h('label', { class: 'field' }, h('span', { text: 'কোড' }),
            h('input', { key: `c${n}`, class: 'code-input', name: 'code', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, required: true })),
          h('div', { class: 'row' },
            h('button', { class: 'btn primary', type: 'submit', disabled: sending.value, text: sending.value ? 'যাচাই হচ্ছে…' : 'লগইন করুন' }),
            h('button', { class: 'link', type: 'button', onclick: () => { stage.value = 'email'; error.value = ''; info.value = ''; draw(); }, text: 'ইমেইল বদলান' })),
          h('div', { class: 'row' },
            h('button', { class: 'link resend', type: 'button', disabled: sending.value || w > 0, onclick: resend,
              text: w > 0 ? `আবার কোড পাঠান (${bn(w)} সেকেন্ড)` : 'আবার কোড পাঠান' }))]));
    };
  },
});
