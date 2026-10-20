// Lifolo ওয়েব অ্যাডমিন প্যানেল — বাম মেনু, ডানে পুরো পাতা।
// নিরাপত্তা: ব্যবহারকারীর লেখা সবসময় লেখা হিসেবে বসে (কখনো innerHTML নয়); লগইন httpOnly কুকিতে।
import { Teleport, defineComponent, h as vh, onMounted, onUnmounted } from 'vue';
import { h } from './h';
import { AUTO_REFRESH_MS, IDLE_MS, S, WARN_MS, loadOverview, logout, route, start } from './state';
import Login from './Login';
import Shell from './Shell';

export default defineComponent({
  name: 'AdminApp',
  setup() {
    const seen = () => {
      S.lastActive = Date.now();
    };
    const events = ['click', 'keydown', 'pointermove', 'scroll', 'touchstart'];
    const onHash = () => route(false, true);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && S.view === 'shell') S.navOpen = false;
    };
    let timer: ReturnType<typeof setInterval> | undefined;
    onMounted(() => {
      events.forEach((ev) => window.addEventListener(ev, seen, { passive: true }));
      window.addEventListener('hashchange', onHash);
      document.addEventListener('keydown', onKey);
      timer = setInterval(() => {
        if (!S.loggedIn) {
          S.warn = false;
          return;
        }
        const idle = Date.now() - S.lastActive;
        if (idle > IDLE_MS) {
          S.warn = false;
          logout('প্যানেলে ২ ঘণ্টা কিছু না করায় নিরাপত্তার জন্য লগআউট হয়েছে। আবার লগইন করুন।');
          return;
        }
        S.warn = idle >= IDLE_MS - WARN_MS;
        // পেছনে চুপচাপ শুধু অপেক্ষার সংখ্যা হালনাগাদ (পাতা নতুন করে আঁকা হয় না, লেখা হারায় না)
        if (!document.hidden && !S.busy && Date.now() - S.overviewAt > AUTO_REFRESH_MS) {
          loadOverview().catch(() => {});
        }
      }, 15000);
      start();
    });
    onUnmounted(() => {
      events.forEach((ev) => window.removeEventListener(ev, seen));
      window.removeEventListener('hashchange', onHash);
      document.removeEventListener('keydown', onKey);
      clearInterval(timer);
    });

    return () => [
      S.view === 'boot'
        ? h('p', { class: 'boot', text: 'লোড হচ্ছে…' })
        : S.view === 'login'
          ? vh(Login, { key: S.loginKey })
          : S.view === 'failed'
            ? h('div', { class: 'login-wrap' }, h('div', { class: 'card login' },
              h('p', { class: 'error', role: 'alert', text: S.failMessage }),
              h('button', { class: 'btn quiet', type: 'button', onclick: () => start(), text: 'আবার চেষ্টা করুন' })))
            : vh(Shell),
      // লগআউটের আগে সতর্কবার্তা: "চালু রাখুন" চাপলে আবার পুরো সময় পাওয়া যায়
      vh(Teleport, { to: 'body' }, [
        h('div', { class: 'idle-warn', role: 'alertdialog', 'aria-live': 'assertive', hidden: !S.warn },
          h('span', { text: 'অনেকক্ষণ কিছু করেননি। ২ মিনিটের মধ্যে নিজে থেকে লগআউট হবে।' }),
          h('button', { class: 'btn primary', type: 'button', onclick: () => {
            S.lastActive = Date.now();
            S.warn = false;
            loadOverview().catch(() => {}); // সার্ভারের সময়ও নতুন করে
          }, text: 'চালু রাখুন' })),
        // ছবি বড় করে দেখা (পর্দাজুড়ে, চাপলে বন্ধ)
        S.zoom
          ? h('div', { class: 'photo-zoom', role: 'dialog', 'aria-label': S.zoom.label, onclick: () => { S.zoom = null; } },
            h('img', { src: S.zoom.src, alt: S.zoom.label }), h('div', { class: 'cap', text: `${S.zoom.label} — বন্ধ করতে চাপুন` }))
          : null,
      ]),
    ];
  },
});
