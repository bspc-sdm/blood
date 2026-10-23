<script setup lang="ts">
// ডেস্কটপের ওপরের স্তর (ওয়েবের App.vue-র বাইরে, আলাদা ছোট অ্যাপ):
//  • পিন সেট করা (ব্যবসা থাকলে বাধ্যতামূলক)  • লক পর্দা  • মালিকের পিন চাওয়া  • ৭ দিন / ঘড়ির সতর্কতা  • সিঙ্কের ছোট অবস্থা
import { computed, onBeforeUnmount, ref } from 'vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { router } from '@/router';
import { offline } from '..';
import { T } from '../text';
import type { OfflineStatus } from '../types';
import { grace, hasBusiness, pin } from './boot';
import { validPin, WIPE_AFTER, type UnlockResult } from './pin';

const s = useSession();
const ps = pin.state;
const gs = grace.state;

const st = ref<OfflineStatus | null>(null);
const off = offline.onChange((x) => (st.value = x));
onBeforeUnmount(off);

const needSetup = computed(() => !!s.uid && s.workLoaded && hasBusiness() && !ps.hasOwner);
const showLock = computed(() => !!s.uid && ps.locked);

// ---- পিন সেট
const p1 = ref('');
const p2 = ref('');
const setErr = ref('');
const setBusy = ref(false);
async function savePin() {
  setErr.value = '';
  if (!validPin(p1.value)) return void (setErr.value = T.pinShort());
  if (p1.value !== p2.value) return void (setErr.value = T.pinMismatch());
  setBusy.value = true;
  try {
    await pin.setPin('owner', p1.value);
    p1.value = p2.value = '';
  } catch (e) {
    setErr.value = String(e);
  } finally {
    setBusy.value = false;
  }
}

// ---- লক / মালিকের পিন
const code = ref('');
const err = ref('');
const busy = ref(false);
const now = ref(Date.now());
const tick = setInterval(() => (now.value = Date.now()), 1000);
onBeforeUnmount(() => clearInterval(tick));
const waitS = computed(() => Math.max(0, Math.ceil((ps.until - now.value) / 1000)));

function explain(r: UnlockResult) {
  if (r.ok) return '';
  if (r.wiped) return T.wiped();
  if (r.waitMs > 0) return T.waitPin(Math.ceil(r.waitMs / 1000));
  return r.left <= 5 ? `${T.wrongPin()} — ${T.wipeWarn(r.left)}` : T.wrongPin();
}

async function submit(owner: boolean) {
  if (busy.value || !code.value) return;
  busy.value = true;
  err.value = '';
  try {
    const r = owner ? await pin.answerOwner(code.value) : await pin.unlock(code.value);
    err.value = explain(r);
  } finally {
    code.value = '';
    busy.value = false;
  }
}

async function forgot() {
  await s.signOut();
  pin.unload();
}

const banner = computed(() => {
  if (!s.uid || !offline.isDesktop()) return '';
  if (gs.gate === 'clock') return T.clockWrong();
  if (gs.gate === 'login_expired') return T.loginExpired();
  if (gs.warn) return T.graceWarn(gs.offlineDays);
  return '';
});

const pill = computed(() => {
  const x = st.value;
  if (!x || !s.uid || !offline.isDesktop()) return '';
  if (x.revoked) return T.revoked();
  if (x.syncing && x.pending) return T.syncing();
  const parts: string[] = [];
  if (!x.online) parts.push(T.offlineNow());
  if (x.pending) parts.push(T.pendingN(x.pending));
  if (x.rejected) parts.push(T.rejectedN(x.rejected));
  return parts.join(' · ');
});
</script>

<template>
  <div v-if="banner" class="lfd-banner" role="status">{{ banner }}</div>

  <button v-if="pill && !showLock" type="button" class="lfd-pill" :class="{ bad: st?.rejected || st?.revoked }" @click="router.push('/desktop/settings')">
    {{ pill }}
  </button>

  <div v-if="needSetup && !showLock" class="lfd-cover" role="dialog" aria-modal="true">
    <form class="lfd-card" @submit.prevent="savePin">
      <div class="t-title-l">{{ T.setPinTitle() }}</div>
      <p class="t-body-m muted">{{ T.pinHint() }}</p>
      <input v-model="p1" v-focus class="lfd-pin" type="password" inputmode="numeric" autocomplete="new-password" maxlength="6" :aria-label="T.newPin()" :placeholder="T.newPin()" />
      <input v-model="p2" class="lfd-pin" type="password" inputmode="numeric" autocomplete="new-password" maxlength="6" :aria-label="T.againPin()" :placeholder="T.againPin()" />
      <p v-if="setErr" class="t-body-s lfd-err">{{ setErr }}</p>
      <p class="t-body-s muted">{{ T.diskHint() }}</p>
      <button class="btn block" type="submit" :disabled="setBusy">{{ T.save() }}</button>
    </form>
  </div>

  <div v-if="showLock" class="lfd-cover solid" role="dialog" aria-modal="true">
    <form class="lfd-card" @submit.prevent="submit(false)">
      <div class="lfd-logo" aria-hidden="true" />
      <div class="t-title-l">{{ T.lockTitle() }}</div>
      <input v-model="code" v-focus class="lfd-pin" type="password" inputmode="numeric" autocomplete="off" maxlength="6" :aria-label="T.enterPin()" :placeholder="T.enterPin()" :disabled="waitS > 0" />
      <p v-if="waitS > 0" class="t-body-s lfd-err">{{ T.waitPin(waitS) }}</p>
      <p v-else-if="err" class="t-body-s lfd-err">{{ err }}</p>
      <p v-else-if="ps.fails >= 5" class="t-body-s lfd-err">{{ T.wipeWarn(WIPE_AFTER - ps.fails) }}</p>
      <button class="btn block" type="submit" :disabled="busy || waitS > 0 || !code">{{ T.unlock() }}</button>
      <button class="btn text block" type="button" @click="forgot">{{ T.forgotPin() }}</button>
      <p class="t-body-s muted">{{ T.forgotHint() }}</p>
    </form>
  </div>

  <div v-if="ps.asking && !showLock" class="lfd-cover" role="dialog" aria-modal="true">
    <form class="lfd-card" @submit.prevent="submit(true)">
      <div class="t-title-l">{{ T.ownerNeeded() }}</div>
      <input v-model="code" v-focus class="lfd-pin" type="password" inputmode="numeric" autocomplete="off" maxlength="6" :aria-label="T.ownerPin()" :placeholder="T.ownerPin()" :disabled="waitS > 0" />
      <p v-if="waitS > 0" class="t-body-s lfd-err">{{ T.waitPin(waitS) }}</p>
      <p v-else-if="err" class="t-body-s lfd-err">{{ err }}</p>
      <div class="row gap-8">
        <button class="btn text grow" type="button" @click="pin.cancelAsk()">{{ T.cancel() }}</button>
        <button class="btn grow" type="submit" :disabled="busy || waitS > 0 || !code">{{ tr('OK', 'ঠিক আছে') }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.lfd-cover {
  position: fixed;
  inset: 0;
  z-index: 2147483000;
  display: grid;
  place-items: center;
  padding: 16px;
  background: var(--scrim);
}
.lfd-cover.solid {
  background: var(--bg);
}
.lfd-card {
  width: min(380px, 100%);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 24px;
  border-radius: 20px;
  background: var(--surface-lowest);
  color: var(--on-surface);
  box-shadow: var(--shadow-high);
  text-align: center;
}
.lfd-logo {
  width: 56px;
  height: 56px;
  margin: 0 auto;
  border-radius: 50%;
  background: var(--primary) url('/w/icon.svg') center / cover no-repeat;
}
.lfd-pin {
  font: inherit;
  font-size: 24px;
  letter-spacing: 0.4em;
  text-align: center;
  padding: 12px;
  border-radius: 12px;
  border: 1px solid var(--outline);
  background: var(--surface);
  color: var(--on-surface);
}
.lfd-pin:focus {
  outline: 2px solid var(--primary);
  outline-offset: 1px;
}
.lfd-err {
  color: var(--error);
}
.lfd-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2147482000;
  padding: 6px 16px;
  text-align: center;
  background: var(--error-container);
  color: var(--on-error-container);
  font-size: 14px;
}
.lfd-pill {
  position: fixed;
  left: 16px;
  bottom: 16px;
  z-index: 2147482000;
  padding: 6px 14px;
  border-radius: 999px;
  border: 0;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  background: var(--secondary-container);
  color: var(--on-secondary-container);
  box-shadow: var(--shadow);
}
.lfd-pill.bad {
  background: var(--error-container);
  color: var(--on-error-container);
}
</style>
