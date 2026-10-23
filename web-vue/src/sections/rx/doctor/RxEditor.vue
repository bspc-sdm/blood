<script setup lang="ts">
// প্রেসক্রিপশন লেখা (শুধু ডাক্তার) — অ্যাপের RxEditorDialog + MedEditDialog, প্যানেলের writeView।
// কম্পিউটারে: ওষুধ এক সারিতে (নাম খুঁজে বাছা → নিয়ম → দিন → নোট; Enter-এ পরের ঘর, নোটে Enter-এ নতুন ওষুধ),
// Ctrl+Enter-এ দেওয়া; দেওয়ার আগে কাগজটা দেখে নিশ্চিত করা (পরে আর বদলানো যায় না)।
import { computed, nextTick, ref, useId } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, Icon, Select, TextArea, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { useNav } from '@/lib/nav';
import {
  COMMON_TESTS, FINDINGS, FOLLOW, MAX_MEDS, SELF, ageText, findingLabel, genderLabel, targetBody, useRxInvalidate, type LabTest, type RxMed, type RxTarget,
} from './model';
import type { Link } from './patients';
import { DAY_MS, newMed, type MedRow } from './write';
import MedRowView from './MedRowView.vue';
import DraftPaper from './DraftPaper.vue';
import { T as PT, type RxKind } from '@/lib/practice';

// kind (lib/practice.ts): full — আগের মতো; free — অ-এলোপ্যাথি, ওষুধ নিজে লেখা; advice — ফিজিও, শুধু পরামর্শ ও ব্যায়াম;
// notes — সাইকোলজিস্ট, শুধু সেশন নোট/পরামর্শ (ওষুধ নেই, সাইকিয়াট্রিস্টের কথা)
const props = withDefaults(defineProps<{ target: RxTarget; chambers: string[]; doctorName: string; degrees: string; link: Link; kind?: RxKind; headTag?: string | null }>(), {
  kind: 'full',
  headTag: null,
});
const medsOn = computed(() => props.kind === 'full' || props.kind === 'free');
const emit = defineEmits<{ otherPatient: [] }>();
const nav = useNav();
const invalidate = useRxInvalidate();
const name = ref(props.target.name);
const age = ref(props.target.age != null ? num(props.target.age) : '');
const gender = ref(props.target.gender ?? '');
const complaints = ref('');
const findings = ref<Record<string, string>>({});
const diagnosis = ref('');
const freeRow = (): MedRow => (props.kind === 'free' ? { ...newMed(), dose: '', timing: '' } : newMed());
const meds = ref<MedRow[]>([freeRow()]);
const tests = ref<string[]>([]);
const testInput = ref('');
const testPlace = ref('');
const advice = ref('');
const followDays = ref(0);
const followNote = ref('');
const chamber = ref(0);
const confirm = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const listId = 'l' + useId().replace(/[^a-zA-Z0-9-]/g, '');
const labtests = useCall<{ tests: LabTest[] }>('labtests_get', SELF, { staleTime: 30 * 60_000 });
const testNames = computed(() => {
  const out = new Set<string>();
  for (const t of labtests.data?.tests ?? []) {
    if (t.hidden) continue;
    out.add(t.name);
  }
  return [...out];
});

const filled = computed(() => (medsOn.value ? meds.value.filter((m) => m.name.trim()) : []));
const canGive = computed(() => !!name.value.trim() && (medsOn.value ? filled.value.length > 0 || tests.value.length > 0 || !!advice.value.trim() : !!advice.value.trim()));
const adviceLabel = computed(() => (props.kind === 'advice' ? PT.adviceExercise() : props.kind === 'notes' ? PT.sessionNotes() : tr('Advice', 'পরামর্শ')));
const setMed = (key: number, patch: Partial<MedRow>) => (meds.value = meds.value.map((m) => (m.key === key ? { ...m, ...patch } : m)));
function addMed() {
  if (meds.value.length >= MAX_MEDS) return;
  const m = freeRow();
  meds.value = [...meds.value, m];
  // নতুন ওষুধের সারি এলে তার নামের ঘরে
  nextTick(() => document.querySelector<HTMLInputElement>(`[data-med="${m.key}"] [data-f="name"]`)?.focus());
}
const removeMed = (key: number) => (meds.value = meds.value.length > 1 ? meds.value.filter((m) => m.key !== key) : [freeRow()]);
function addTest(v: string) {
  const t = v.trim().slice(0, 80);
  if (t && !tests.value.includes(t)) tests.value = [...tests.value, t];
  testInput.value = '';
}
const toMed = (m: MedRow): RxMed => {
  const days = Math.min(365, Math.max(0, parseInt(toAsciiDigits(m.days), 10) || 0));
  // অ-এলোপ্যাথি: নিজে লেখা লাইন — নাম, নিয়ম (ফাঁকাসহ), দিন, নোট (সার্ভারের parseFreeMedicines)
  if (props.kind === 'free') return { name: m.name.trim(), dose: m.dose.trim().replace(/\s+/g, ' '), days, note: m.note.trim() };
  return { name: m.name.trim(), form: m.form.trim(), strength: m.strength.trim(), dose: toAsciiDigits(m.dose).replace(/\s+/g, ''), timing: m.timing, days, note: m.note.trim() };
};
function ask() {
  err.value = null;
  if (!canGive.value) {
    err.value = !name.value.trim() ? tr("Write the patient's name", 'রোগীর নাম লিখুন') : medsOn.value ? tr('Add a medicine, a test or advice', 'অন্তত একটা ওষুধ, পরীক্ষা বা পরামর্শ দিন') : PT.needAdvice();
    return;
  }
  confirm.value = true;
}

async function issue() {
  busy.value = true;
  err.value = null;
  try {
    const a = toAsciiDigits(age.value.trim());
    const ti = testInput.value.trim();
    const r = await call<{ id: string; code: string }>('rx_create', {
      ...targetBody(props.target),
      patient: { name: name.value.trim(), age: a ? Number(a) : null, gender: gender.value },
      complaints: complaints.value.trim(),
      findings: Object.fromEntries(FINDINGS.map((k) => [k, (findings.value[k] ?? '').trim()])),
      diagnosis: diagnosis.value.trim(),
      medicines: filled.value.map(toMed),
      tests: medsOn.value ? [...tests.value, ...(ti && !tests.value.includes(ti) ? [ti] : [])] : [],
      testPlace: medsOn.value ? testPlace.value.trim() : '',
      advice: advice.value.trim(),
      followUpDays: followDays.value,
      followUpNote: followDays.value ? followNote.value.trim() : '',
      ...(props.chambers.length ? { chamberIndex: Math.min(chamber.value, props.chambers.length - 1) } : {}),
    });
    invalidate();
    toast(tr('Prescription given', 'প্রেসক্রিপশন দেওয়া হয়েছে'));
    nav(props.link('/view/' + r.id));
  } catch (e) {
    err.value = errorText(e);
    confirm.value = false;
  }
  busy.value = false;
}

function onFormKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    ask();
  }
}
function onTestKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
    e.preventDefault();
    addTest(testInput.value);
  }
}
const ageNum = computed(() => (age.value ? Number(toAsciiDigits(age.value)) : null));
const patientText = computed(() => [name.value.trim(), ageText(ageNum.value), genderLabel(gender.value)].filter(Boolean).join(' · '));
const findingsText = computed(() => FINDINGS.filter((k) => findings.value[k]?.trim()).map((k) => findingLabel(k) + ': ' + findings.value[k].trim()).join(' · '));
const followText = computed(() => (followDays.value ? formatDate(todayUtcMidnight() + followDays.value * DAY_MS) + (followNote.value.trim() ? ' · ' + followNote.value.trim() : '') : ''));
</script>

<template>
  <form class="rxd-editor" novalidate :aria-label="tr('Write a prescription', 'প্রেসক্রিপশন লিখুন')" @submit.prevent="ask" @keydown="onFormKey">
    <div class="rxd-grid">
      <!-- রোগী -->
      <section class="card col gap-12">
        <div class="row">
          <h2 class="rxd-sec grow">{{ tr('Patient', 'রোগী') }}</h2>
          <Button variant="text" small @click="emit('otherPatient')">{{ tr('Other patient', 'অন্য রোগী') }}</Button>
        </div>
        <TextInput :label="tr('Name', 'নাম')" :model-value="name" autocomplete="off" @update:model-value="(v) => (name = v.slice(0, 80))" />
        <div class="row top wrap gap-12">
          <div class="rxd-age">
            <TextInput :label="tr('Age', 'বয়স')" inputmode="numeric" :model-value="age" @update:model-value="(v) => (age = v.replace(/[^0-9০-৯]/g, '').slice(0, 3))" />
          </div>
          <div class="field">
            <span class="label">{{ tr('Gender', 'লিঙ্গ') }}</span>
            <ChoiceChips
              :options="[{ value: 'male', label: genderLabel('male') }, { value: 'female', label: genderLabel('female') }]"
              :model-value="gender"
              @update:model-value="(v: string) => (gender = gender === v ? '' : v)"
            />
          </div>
        </div>
        <Select
          v-if="chambers.length > 1"
          :label="tr('Chamber', 'চেম্বার')"
          :model-value="String(chamber)"
          :options="chambers.map((c, i) => ({ value: String(i), label: c || '-' }))"
          @update:model-value="(v) => (chamber = Number(v))"
        />
        <span v-else-if="chambers.length === 1 && chambers[0]" class="t-body-s muted row gap-6"><Icon name="local_hospital" class="rxd-ic16" />{{ chambers[0] }}</span>
      </section>

      <!-- সমস্যা ও পরীক্ষায় পাওয়া -->
      <section class="card col gap-12">
        <h2 class="rxd-sec">{{ tr('Complaints & findings', 'সমস্যা ও পরীক্ষায় পাওয়া') }}</h2>
        <TextArea :label="tr('Complaints', 'সমস্যা')" :rows="2" class="textarea rxd-ta" :model-value="complaints" @update:model-value="(v) => (complaints = v.slice(0, 1000))" />
        <div v-if="kind !== 'notes'" class="rxd-findings">
          <TextInput
            v-for="k in FINDINGS"
            :key="k"
            :label="findingLabel(k)"
            :model-value="findings[k] ?? ''"
            autocomplete="off"
            @update:model-value="(v) => (findings = { ...findings, [k]: v.slice(0, 20) })"
          />
        </div>
        <TextInput v-if="kind !== 'notes'" :label="kind === 'advice' ? PT.assessment() : tr('Diagnosis', 'রোগনির্ণয়')" :model-value="diagnosis" autocomplete="off" @update:model-value="(v) => (diagnosis = v.slice(0, 500))" />
      </section>

      <!-- ফিজিও/সাইকোলজিস্ট: ওষুধ নেই -->
      <section v-if="kind === 'advice'" class="card tonal col rxd-span" data-testid="rx-no-meds">
        <span class="t-body-m">{{ PT.noMedsPhysio() }}</span>
      </section>
      <section v-else-if="kind === 'notes'" class="card tonal col rxd-span" data-testid="rx-psychiatrist" role="note">
        <span class="t-title-s">{{ PT.psychiatrist() }}</span>
      </section>
      <!-- ওষুধ -->
      <section v-if="medsOn" class="card col gap-12 rxd-span">
        <div class="row">
          <h2 class="rxd-sec grow"><span class="rxd-rx" aria-hidden="true">℞</span> {{ tr('Medicines', 'ওষুধ') }}</h2>
          <span class="t-body-s muted rxd-keys">{{ tr('Enter: next box · Enter on note: new medicine', 'Enter: পরের ঘর · নোটে Enter: নতুন ওষুধ') }}</span>
        </div>
        <p v-if="kind === 'free'" class="t-body-s muted" data-testid="rx-free-note">{{ PT.freeMeds() }}</p>
        <div class="rxd-meds" role="group" :aria-label="tr('Medicines', 'ওষুধ')">
          <div :class="['rxd-med rxd-med-head', kind === 'free' && 'rxd-free']" aria-hidden="true">
            <span />
            <span>{{ tr('Medicine name', 'ওষুধের নাম') }}</span>
            <span v-if="kind !== 'free'">{{ tr('Form', 'ধরন') }}</span>
            <span v-if="kind !== 'free'">{{ tr('Strength', 'মাত্রা') }}</span>
            <span>{{ tr('How to take', 'খাওয়ার নিয়ম') }}</span>
            <span v-if="kind !== 'free'">{{ tr('Food', 'খাবার') }}</span>
            <span>{{ tr('Days', 'দিন') }}</span>
            <span>{{ tr('Note', 'নোট') }}</span>
            <span />
          </div>
          <MedRowView
            v-for="(m, i) in meds"
            :key="m.key"
            :m="m"
            :n="i + 1"
            :last="i === meds.length - 1"
            :free="kind === 'free'"
            @change="(p) => setMed(m.key, p)"
            @remove="removeMed(m.key)"
            @add-next="addMed"
          />
        </div>
        <div>
          <Button variant="tonal" icon="add" :disabled="meds.length >= MAX_MEDS" @click="addMed">{{ tr('Add medicine', 'ওষুধ যোগ করুন') }}</Button>
        </div>
      </section>

      <!-- পরীক্ষা -->
      <section v-if="medsOn" class="card col gap-12">
        <h2 class="rxd-sec">{{ tr('Tests', 'পরীক্ষা') }}</h2>
        <ChoiceChips v-model="tests" multi :options="[...COMMON_TESTS, ...tests.filter((t) => !COMMON_TESTS.includes(t))].map((t) => ({ value: t, label: t }))" />
        <div class="row top gap-8">
          <div class="grow">
            <TextInput
              :label="tr('Other test', 'অন্য পরীক্ষা')"
              :list="listId + 'tests'"
              :model-value="testInput"
              autocomplete="off"
              @update:model-value="(v) => (testInput = v.slice(0, 80))"
              @keydown="onTestKey"
            />
          </div>
          <Button variant="outline" class="rxd-field-btn" :disabled="!testInput.trim()" @click="addTest(testInput)">{{ tr('Add', 'যোগ') }}</Button>
        </div>
        <datalist :id="listId + 'tests'"><option v-for="t in testNames" :key="t" :value="t" /></datalist>
        <TextInput
          v-if="tests.length"
          :label="tr('Suggest where to test (optional)', 'কোথায় করাবেন (ঐচ্ছিক)')"
          :hint="tr('The patient can test anywhere they like', 'রোগী চাইলে অন্য জায়গাতেও করাতে পারবেন')"
          :placeholder="tr('e.g. Popular Diagnostic, Mymensingh', 'যেমন: পপুলার ডায়াগনস্টিক, ময়মনসিংহ')"
          :model-value="testPlace"
          autocomplete="off"
          @update:model-value="(v) => (testPlace = v.slice(0, 200))"
        />
      </section>

      <!-- পরামর্শ ও আবার দেখানো -->
      <section class="card col gap-12">
        <h2 class="rxd-sec">{{ adviceLabel }}</h2>
        <TextArea :label="adviceLabel" :rows="medsOn ? 3 : 8" class="textarea rxd-ta" :model-value="advice" @update:model-value="(v) => (advice = v.slice(0, medsOn ? 1000 : 3000))" />
        <div class="field">
          <span class="label">{{ tr('Come again', 'আবার দেখাবেন') }}</span>
          <ChoiceChips v-model="followDays" :options="[0, ...FOLLOW].map((d) => ({ value: d, label: d ? tr(`${d} days`, `${num(d)} দিন পর`) : tr('No', 'দরকার নেই') }))" />
        </div>
        <TextInput
          v-if="followDays > 0"
          :label="tr('Note (e.g. bring the reports)', 'নোট (যেমন: রিপোর্ট নিয়ে আসবেন)')"
          :hint="formatDate(todayUtcMidnight() + followDays * DAY_MS)"
          :model-value="followNote"
          autocomplete="off"
          @update:model-value="(v) => (followNote = v.slice(0, 80))"
        />
      </section>
    </div>

    <!-- নিচে আটকে থাকা বার -->
    <div class="rxd-bar">
      <div class="col gap-2 grow">
        <span class="t-title-s ellipsis">{{ [name.trim() || tr('Patient', 'রোগী'), ageText(ageNum), genderLabel(gender)].filter(Boolean).join(' · ') }}</span>
        <span v-if="medsOn" class="t-body-s muted">{{ [tr(`${filled.length} medicine(s)`, `${num(filled.length)}টা ওষুধ`), tr(`${tests.length} test(s)`, `${num(tests.length)}টা পরীক্ষা`)].join(' · ') }}<span class="rxd-keys"> · Ctrl+Enter</span></span>
        <ErrorText :text="err" />
      </div>
      <Button icon="check_circle" :busy="busy" @click="ask">{{ tr('Give prescription', 'প্রেসক্রিপশন দিন') }}</Button>
    </div>

    <Dialog v-if="confirm" size="wide" :title="tr('Give this prescription?', 'প্রেসক্রিপশনটা দেবেন?')" @close="() => !busy && (confirm = false)">
      <p class="t-body-m muted">{{ tr('It can\'t be edited afterwards; you can only cancel it and write a new one.', 'দেওয়ার পর আর বদলানো যাবে না; শুধু বাতিল করে নতুন লেখা যাবে।') }}</p>
      <DraftPaper
        :doctor-name="doctorName"
        :kind="kind"
        :head-tag="headTag"
        :degrees="degrees"
        :chamber="chambers.length ? chambers[Math.min(chamber, chambers.length - 1)] : ''"
        :patient="patientText"
        :complaints="complaints.trim()"
        :findings="findingsText"
        :diagnosis="diagnosis.trim()"
        :meds="filled.map(toMed)"
        :tests="tests"
        :test-place="testPlace.trim()"
        :advice="advice.trim()"
        :follow="followText"
      />
      <ErrorText :text="err" />
      <template #actions>
        <Button variant="text" :disabled="busy" @click="confirm = false">{{ tr('Check again', 'আবার দেখি') }}</Button>
        <Button icon="check_circle" :busy="busy" @click="issue">{{ tr('Give', 'দিন') }}</Button>
      </template>
    </Dialog>
  </form>
</template>
