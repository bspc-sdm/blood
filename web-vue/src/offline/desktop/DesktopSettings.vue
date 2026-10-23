<script setup lang="ts">
// ডেস্কটপ সেটিংস (/desktop/settings, শুধু ডেস্কটপ অ্যাপে): প্রিন্টার আর টেস্ট প্রিন্ট, সিঙ্ক, পিন, Windows-এর সাথে চালু,
// নোটিফিকেশন, আপডেট। মালিকের পিন লাগে (নিরাপত্তা পর্যালোচনা I7)।
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { disable as autoOff, enable as autoOn, isEnabled as autoIsOn } from '@tauri-apps/plugin-autostart';
import FormPage from '@/shell/FormPage.vue';
import { Button, Select, Segmented, Switch, toast } from '@/ui/kit';
import { formatDateTime } from '@/lib/format';
import { router } from '@/router';
import { offline } from '..';
import { T } from '../text';
import type { OfflineStatus } from '../types';
import { engine, pin } from './boot';
import { appInfo, listPrinters, type PrinterInfo } from './native';
import { notifyOn, setNotifyOn } from './notify';
import { validPin } from './pin';
import { printInvoice, printPrefs, savePrintPrefs, testInvoice } from './printing';
import { checkUpdate, installUpdate, updateState } from './update';

const allowed = ref(false);
const printers = ref<PrinterInfo[]>([]);
const loadingPrinters = ref(false);
const printing = ref(false);
const version = ref('');
const auto = ref(false);
const notif = ref(notifyOn());
const st = ref<OfflineStatus | null>(null);
const rejected = ref<Awaited<ReturnType<typeof engine.rejectedOps>>>([]);
const dev = computed(() => (st.value ? engine.device() : null));
const off = offline.onChange((x) => {
  st.value = x;
  void engine.rejectedOps().then((r) => (rejected.value = r));
});
onBeforeUnmount(off);

async function refreshPrinters() {
  loadingPrinters.value = true;
  try {
    printers.value = await listPrinters();
    if (!printPrefs.printer && printers.value.length) savePrintPrefs({ printer: printers.value[0].name });
  } catch (e) {
    toast(String(e));
  } finally {
    loadingPrinters.value = false;
  }
}

onMounted(async () => {
  allowed.value = await pin.requireOwner();
  if (!allowed.value) {
    void router.replace('/');
    return;
  }
  void refreshPrinters();
  appInfo()
    .then((i) => (version.value = i.version))
    .catch(() => {});
  autoIsOn()
    .then((v) => (auto.value = v))
    .catch(() => {});
});

const printerOptions = computed(() => printers.value.map((p) => ({ value: p.name, label: p.default ? `${p.name} ★` : p.name })));
const paperOptions = computed(() => [
  { value: '58' as const, label: T.mm58() },
  { value: '80' as const, label: T.mm80() },
  { value: 'A4' as const, label: 'A4' },
]);

async function testPrint() {
  printing.value = true;
  try {
    await printInvoice(testInvoice(), { paper: printPrefs.paper, englishDigits: printPrefs.englishDigits });
    toast(T.printed());
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e));
  } finally {
    printing.value = false;
  }
}

async function setAuto(v: boolean) {
  try {
    if (v) await autoOn();
    else await autoOff();
    auto.value = await autoIsOn();
  } catch (e) {
    toast(String(e));
  }
}

function setNotif(v: boolean) {
  notif.value = v;
  setNotifyOn(v);
}

// ---- পিন
const which = ref<'owner' | 'counter'>('owner');
const p1 = ref('');
const p2 = ref('');
const pinErr = ref('');
async function savePin() {
  pinErr.value = '';
  if (!validPin(p1.value)) return void (pinErr.value = T.pinShort());
  if (p1.value !== p2.value) return void (pinErr.value = T.pinMismatch());
  try {
    await pin.setPin(which.value, p1.value);
    p1.value = p2.value = '';
    toast(T.pinSaved());
  } catch (e) {
    pinErr.value = String(e);
  }
}
const pinTabs = computed(() => [
  { value: 'owner' as const, label: T.ownerPin() },
  { value: 'counter' as const, label: T.counterPin() },
]);

const syncing = ref(false);
async function syncNow() {
  syncing.value = true;
  try {
    await offline.syncNow();
  } finally {
    syncing.value = false;
  }
}
</script>

<template>
  <FormPage :title="T.settings()">
    <div v-if="allowed" class="col gap-24">
      <section class="col gap-12">
        <h2 class="t-title-m">{{ T.printer() }}</h2>
        <p class="t-body-s muted">{{ T.printerHint() }}</p>
        <Segmented :model-value="printPrefs.paper" :options="paperOptions" :label="T.paper()" @update:model-value="(v) => savePrintPrefs({ paper: v })" />
        <template v-if="printPrefs.paper !== 'A4'">
          <Select
            :label="T.choosePrinter()"
            :options="printerOptions"
            :model-value="printPrefs.printer"
            :placeholder="printers.length ? undefined : T.noPrinters()"
            @update:model-value="(v) => savePrintPrefs({ printer: v })"
          />
          <Switch :model-value="printPrefs.drawer" :label="T.drawer()" @update:model-value="(v) => savePrintPrefs({ drawer: v })" />
        </template>
        <Switch :model-value="printPrefs.englishDigits" :label="T.digitsEn()" @update:model-value="(v) => savePrintPrefs({ englishDigits: v })" />
        <div class="row gap-8">
          <Button :busy="printing" @click="testPrint">{{ T.testPrint() }}</Button>
          <Button variant="text" :busy="loadingPrinters" @click="refreshPrinters">{{ T.refresh() }}</Button>
        </div>
      </section>

      <section class="col gap-8">
        <h2 class="t-title-m">{{ T.sync() }}</h2>
        <template v-if="st">
          <p v-if="st.revoked" class="t-body-m lfd-bad">{{ T.revoked() }}</p>
          <p v-else class="t-body-m">{{ st.pending ? T.pendingN(st.pending) : T.allSynced() }}</p>
          <p v-if="!st.online" class="t-body-s muted">{{ T.offlineNow() }}</p>
          <p v-if="st.lastSyncAt" class="t-body-s muted">{{ T.lastSync(formatDateTime(st.lastSyncAt)) }}</p>
          <p v-if="dev" class="t-body-s muted">{{ T.device() }}: {{ T.series(dev.series) }}</p>
          <div v-if="rejected.length" class="col gap-4">
            <p class="t-body-m lfd-bad">{{ T.rejectedN(rejected.length) }}</p>
            <p v-for="r in rejected" :key="r.opId" class="t-body-s muted">{{ r.no || r.type }} — {{ r.error }} ({{ formatDateTime(r.at) }})</p>
          </div>
          <div><Button variant="tonal" :busy="syncing || st.syncing" :disabled="st.revoked" @click="syncNow">{{ T.syncNow() }}</Button></div>
        </template>
      </section>

      <section class="col gap-8">
        <h2 class="t-title-m">{{ T.pin() }}</h2>
        <p class="t-body-s muted">{{ T.pinHint() }}</p>
        <Segmented v-model="which" :options="pinTabs" :label="T.pin()" />
        <p v-if="which === 'counter'" class="t-body-s muted">{{ T.counterHint() }}</p>
        <form class="col gap-8" @submit.prevent="savePin">
          <input v-model="p1" class="lfd-input" type="password" inputmode="numeric" autocomplete="new-password" maxlength="6" :placeholder="T.newPin()" :aria-label="T.newPin()" />
          <input v-model="p2" class="lfd-input" type="password" inputmode="numeric" autocomplete="new-password" maxlength="6" :placeholder="T.againPin()" :aria-label="T.againPin()" />
          <p v-if="pinErr" class="t-body-s lfd-bad">{{ pinErr }}</p>
          <div class="row gap-8">
            <Button type="submit" :disabled="which === 'counter' && !pin.state.hasOwner">{{ pin.state.hasOwner || which === 'counter' ? T.changePin() : T.setPin() }}</Button>
            <Button v-if="which === 'counter' && pin.state.hasCounter" variant="text" @click="pin.removeCounter()">{{ T.removeCounter() }}</Button>
          </div>
        </form>
        <p class="t-body-s muted">{{ T.diskHint() }}</p>
      </section>

      <section class="col gap-4">
        <Switch :model-value="auto" :label="T.startup()" @update:model-value="setAuto" />
        <Switch :model-value="notif" :label="T.notifications()" :sub="T.notifyHint()" @update:model-value="setNotif" />
      </section>

      <section class="col gap-8">
        <h2 class="t-title-m">{{ T.update() }}</h2>
        <p v-if="version" class="t-body-s muted">{{ T.version(version) }}</p>
        <p v-if="updateState.available" class="t-body-m">{{ T.updateReady(updateState.available) }}</p>
        <p v-else-if="updateState.checkedAt" class="t-body-s muted">{{ T.upToDate() }}</p>
        <p v-if="updateState.installing" class="t-body-s">{{ T.updating() }}</p>
        <p v-if="updateState.error" class="t-body-s lfd-bad">{{ T.updateFailed() }}</p>
        <div class="row gap-8">
          <Button v-if="updateState.available" :busy="updateState.installing" @click="installUpdate">{{ T.updateNow() }}</Button>
          <Button variant="text" :busy="updateState.checking" @click="checkUpdate(true)">{{ T.checkUpdate() }}</Button>
        </div>
      </section>
    </div>
  </FormPage>
</template>

<style scoped>
.lfd-bad {
  color: var(--error);
}
.lfd-input {
  font: inherit;
  font-size: 18px;
  letter-spacing: 0.3em;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--outline);
  background: var(--surface);
  color: var(--on-surface);
  max-width: 240px;
}
</style>
