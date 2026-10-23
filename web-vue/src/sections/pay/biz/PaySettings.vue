<script setup lang="ts">
// ব্যবসা: রোগীরা কোন নম্বরে টাকা পাঠাবেন (pay_methods_get/set) — বিকাশ, নগদ, রকেট, উপায়: নম্বর, পার্সোনাল/মার্চেন্ট, চালু/বন্ধ।
// নতুন বা বদলানো নম্বর ২৪ ঘণ্টা পর চালু (ততক্ষণ আগেরটা; আগে তৈরি পেমেন্টে আগের নম্বরই), প্রতিটা বদলে পুশ আর ইমেইল।
// নিরাপত্তা (P4): বদলাতে নতুন লগইন (401 reauth_required → আবার লগইন করে আবার সেভ), সাম্প্রতিক বদলের পাশে "আমি করিনি" (সব নম্বর
// আটকানো), শুধু মালিক বদলাতে পারেন (সহকারী শুধু দেখেন)। ডাক্তার: চেম্বার অনুযায়ী বুকিংয়ের অগ্রিম (pay_chamber_advance)।
import { computed, reactive, ref, watch } from 'vue';
import { Button, ConfirmDialog, EmptyState, ErrorBox, ErrorText, InfoText, Segmented, Select, SkeletonCards, Switch, TextInput, toast } from '@/ui/kit';
import { ServerError, errorText } from '@/lib/api';
import { isBangla, num, toAsciiDigits } from '@/lib/i18n';
import { formatDateTime } from '@/lib/format';
import { useQ } from '@/lib/query';
import * as api from '../api';
import { PAY_METHOD_KINDS, mobileInput, normMobile, type AcctType, type BizKind, type PayMethodKind } from '../model';
import { payOn } from '../features';
import { T, acctText, methodName } from '../text';
import ReauthDialog from './ReauthDialog.vue';

const props = defineProps<{ kind: BizKind; bizId: string; owner: boolean }>();
const q = useQ<api.MethodsInfo>(() => ({ queryKey: ['pay_methods_get', props.kind, props.bizId], queryFn: () => api.getMethods(props.kind, props.bizId) }));

interface Row { method: PayMethodKind; on: boolean; number: string; type: AcctType }
const rows = reactive<Row[]>(PAY_METHOD_KINDS.map((m) => ({ method: m, on: false, number: '', type: 'personal' })));
interface AdvRow { chamberKey: string; name: string; on: boolean; amount: string; hours: string }
const adv = ref<AdvRow[]>([]);
function fill(info: api.MethodsInfo) {
  for (const r of rows) {
    const m = info.methods.find((x) => x.method === r.method);
    r.on = !!m?.active;
    r.number = m?.number ?? '';
    r.type = m?.type ?? 'personal';
  }
  adv.value = info.chambers.map((c) => {
    const a = info.advance.find((x) => x.chamberKey === c.chamberKey);
    return { chamberKey: c.chamberKey, name: (isBangla() && c.nameBn) || c.name || c.chamberKey, on: !!a, amount: a ? String(a.amount) : '', hours: String(a?.refundableUntilHoursBefore ?? 24) };
  });
}
watch(() => q.data, (d) => d && fill(d), { immediate: true });

const busy = ref(false);
const err = ref<string | null>(null);
const reauth = ref<null | (() => Promise<void>)>(null);
const freezing = ref(false);
const freezeErr = ref<string | null>(null);
const canAdvance = computed(() => props.kind === 'chamber' && props.owner && payOn('pay_chamber_advance'));
const pending = (m: PayMethodKind) => q.data?.methods.find((x) => x.method === m && x.pendingUntil) ?? null;
const HOURS = [0, 2, 6, 12, 24, 48];

function methodsBody() {
  const out = [];
  for (const r of rows) {
    if (!r.on && !r.number.trim()) continue;
    const n = normMobile(r.number);
    if (!n) throw new Error(T.errNumber(methodName(r.method)));
    out.push({ method: r.method, number: n, type: r.type, active: r.on });
  }
  return out;
}
async function run(f: () => Promise<unknown>) {
  busy.value = true;
  err.value = null;
  try {
    await f();
    toast(T.saved());
    void q.refetch();
  } catch (e) {
    // P4: পুরোনো লগইন — আবার লগইন করে একই কাজ আবার
    if (e instanceof ServerError && e.status === 401 && e.code === 'reauth_required') reauth.value = () => f().then(() => void q.refetch());
    else err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function saveMethods() {
  let body: ReturnType<typeof methodsBody>;
  try {
    body = methodsBody();
  } catch (e) {
    err.value = errorText(e);
    return;
  }
  void run(() => api.setMethods(props.kind, props.bizId, { methods: body }));
}
function saveAdvance() {
  const list: api.Advance[] = [];
  for (const a of adv.value.filter((x) => x.on)) {
    const amt = Number(toAsciiDigits(a.amount));
    if (!Number.isInteger(amt) || amt < 1 || amt > 5000) return void (err.value = T.errAdvance());
    list.push({ chamberKey: a.chamberKey, amount: amt, refundableUntilHoursBefore: Number(a.hours) || 0 });
  }
  void run(() => api.setMethods(props.kind, props.bizId, { advance: list }));
}
async function freeze() {
  busy.value = true;
  freezeErr.value = null;
  try {
    await api.freezeMethods(props.kind, props.bizId);
    freezing.value = false;
    toast(T.frozenToast());
    void q.refetch();
  } catch (e) {
    freezeErr.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const changeText = (c: api.MethodChangeRow) =>
  [c.oldLast4 ? '…' + num(c.oldLast4) : '', c.newLast4 ? '…' + num(c.newLast4) : ''].filter(Boolean).join(' → ') || c.change;
</script>

<template>
  <SkeletonCards v-if="q.isPending" :n="2" :h="120" />
  <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
  <div v-else class="col gap-12" data-testid="pay-settings">
    <section class="card col gap-12">
      <h2 class="t-title-m">{{ T.methodsTitle() }}</h2>
      <p class="t-body-s muted">{{ T.methodsIntro() }}</p>
      <p v-if="q.data?.frozen" class="pay-rej t-body-m semi" role="alert" data-testid="pay-frozen">{{ T.frozen() }}</p>
      <div class="pay-cool t-body-s" data-testid="pay-cooloff">{{ T.coolOff() }}</div>
      <template v-if="owner">
        <div v-for="r in rows" :key="r.method" class="pay-method-ed" :data-on="String(r.on)" :data-method="r.method">
          <Switch v-model="r.on" :label="methodName(r.method)" :sub="r.on ? T.use() : undefined" :data-testid="`pm-on-${r.method}`" />
          <div class="pay-method-fields">
            <TextInput :label="T.number()" inputmode="tel" autocomplete="off" :data-testid="`pm-number-${r.method}`" :model-value="r.number" @update:model-value="(v: string) => (r.number = mobileInput(v))" />
            <Segmented v-model="r.type" :label="T.accountType()" :options="[{ value: 'personal', label: acctText('personal') }, { value: 'merchant', label: acctText('merchant') }]" />
          </div>
          <p v-if="pending(r.method)" class="t-body-s" data-testid="pm-pending">{{ T.pendingNumber(num(pending(r.method)!.number), formatDateTime(pending(r.method)!.pendingUntil!)) }}</p>
          <p v-if="r.on && r.type === 'personal'" class="t-body-s muted">{{ T.personalNote() }}</p>
        </div>
        <ErrorText :text="err" />
        <div class="row"><Button icon="check_circle" :busy="busy" data-testid="pm-save" @click="saveMethods">{{ T.save() }}</Button></div>
      </template>
      <template v-else>
        <InfoText :text="T.ownerOnly()" />
        <ul class="pay-list">
          <li v-for="m in q.data?.methods ?? []" :key="m.method">{{ methodName(m.method) }} · {{ num(m.activeNumber) }} · {{ acctText(m.type) }}</li>
        </ul>
      </template>
    </section>

    <!-- সাম্প্রতিক বদল আর "আমি করিনি" (P4) -->
    <section v-if="owner && q.data?.history.length" class="card col gap-8" data-testid="pay-history">
      <h3 class="t-title-s">{{ T.recentChanges() }}</h3>
      <span v-for="(c, i) in q.data.history.slice(0, 10)" :key="i" class="t-body-s">{{ T.changeLine(methodName(c.method), changeText(c), formatDateTime(c.at)) }}</span>
      <div v-if="!q.data.frozen" class="row"><Button small variant="danger" data-testid="pay-not-me" @click="freezing = true">{{ T.notMe() }}</Button></div>
    </section>

    <!-- ডাক্তার: চেম্বার অনুযায়ী অগ্রিম -->
    <section v-if="canAdvance" class="card col gap-8" data-testid="pay-advance">
      <h2 class="t-title-m">{{ T.advanceTitle() }}</h2>
      <p class="t-body-s muted">{{ T.advanceIntro() }}</p>
      <p v-if="!q.data?.methods.some((m) => m.active)" class="t-body-s c-error">{{ T.advanceNeedsMethod() }}</p>
      <EmptyState v-if="!adv.length" icon="stethoscope" :title="T.noChambers()" />
      <div v-for="a in adv" :key="a.chamberKey" class="pay-adv-row" :data-chamber="a.chamberKey">
        <Switch v-model="a.on" :label="a.name" :sub="a.on ? T.advanceOn() : undefined" />
        <div v-if="a.on" class="pay-adv-fields">
          <TextInput :label="T.advanceAmount()" inputmode="numeric" data-testid="adv-amount" :model-value="a.amount" @update:model-value="(v: string) => (a.amount = toAsciiDigits(v).replace(/\D/g, '').slice(0, 5))" />
          <Select v-model="a.hours" :label="T.refundableUntil()" :options="HOURS.map((h) => ({ value: String(h), label: T.hoursBefore(h) }))" />
        </div>
      </div>
      <div v-if="adv.length" class="row"><Button icon="check_circle" :busy="busy" data-testid="adv-save" @click="saveAdvance">{{ T.save() }}</Button></div>
    </section>

    <ReauthDialog v-if="reauth" :retry="reauth" @close="reauth = null" @done="() => { reauth = null; toast(T.saved()); }" />
    <ConfirmDialog v-if="freezing" :title="T.notMeTitle()" :text="T.notMeText()" :confirm="T.notMe()" danger :busy="busy" :error="freezeErr" @confirm="freeze" @close="freezing = false" />
  </div>
</template>
