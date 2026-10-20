<script setup lang="ts">
// কেয়ারগিভার ও পরিবার (অ্যাপের CareAccess.kt → CareScreen)
// - আমার কেয়ারগিভার (সর্বোচ্চ ২ জন, ইমেইল দিয়ে আমন্ত্রণ, তাঁরা রাজি হলে চালু)
// - যাঁদের দেখাশোনা করি: রাজি/না, "ওঁর হয়ে খুলুন" (কেয়ার মোড — উপরে ফিতা দেখায়), সরে যান
// - পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই): সর্বোচ্চ ৬ জন
import { computed, ref, watch } from 'vue';
import { Action, Button, ConfirmDialog, EmptyState, ErrorBox, ErrorText, SkeletonCards, Tag, toast } from '@/ui/kit';
import SubPageBar from '@/shell/SubPageBar.vue';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { setCareMode } from '@/lib/care';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { useNav, useSearchParams } from '@/lib/nav';
import { useBack } from './nav';
import type { CareData, CareLink, FamilyMember } from './care';
import CarePerson from './CarePerson.vue';
import InviteDialog from './InviteDialog.vue';
import MemberDialog from './MemberDialog.vue';

const s = useSession();
const nav = useNav();
const title = computed(() => tr('Caregivers & family', 'কেয়ারগিভার ও পরিবার'));
usePageTitle(title);
const back = useBack('/profile');
const q = useCall<CareData>('care_list', {}, () => ({ enabled: !s.guest, staleTime: 0 }));
const inviting = ref(false);
const member = ref<FamilyMember | 'new' | null>(null);
const confirm = ref<{ text: string; run: () => Promise<unknown> } | null>(null);
const busy = ref(false);
const error = ref<string | null>(null);
// নোটিফিকেশন থেকে (?for=রোগী&rx=প্রেসক্রিপশন): এখনো কেয়ারগিভার হলে রোগীর মোডে সেই প্রেসক্রিপশন, নইলে কারণ
const [params, setParams] = useSearchParams();
watch(
  () => [params.value.get('for'), params.value.get('rx'), q.data] as const,
  ([forUid, forRx, data]) => {
    if (!forUid || !forRx || !data) return;
    const link = data.caringFor.find((c) => c.patientUid === forUid && c.status === 'active');
    if (link) {
      setCareMode({ uid: link.patientUid, name: link.patientName });
      nav(`/prescriptions/${encodeURIComponent(forRx)}`, { replace: true });
    } else {
      error.value = tr("You can't open this prescription now — you're no longer their caregiver", 'এই প্রেসক্রিপশন এখন খোলা যাচ্ছে না — আপনি আর তাঁর কেয়ারগিভার নন');
      setParams({}, { replace: true });
    }
  },
  { immediate: true },
);

async function act(run: () => Promise<unknown>, ok?: string) {
  busy.value = true;
  error.value = null;
  try {
    await run();
    if (ok) toast(ok);
    await q.refetch();
    return true;
  } catch (e) {
    error.value = errorText(e);
    return false;
  } finally {
    busy.value = false;
  }
}

const d = computed(() => q.data);
const nameOf = (c: CareLink) => c.caretakerName || c.caretakerEmail;
const famSub = (m: FamilyMember) => [m.relation || null, m.age != null ? tr(`${m.age} yrs`, `${num(m.age)} বছর`) : null, m.phone ? num(m.phone) : null].filter(Boolean).join(' · ');

function askRemoveCaretaker(c: CareLink) {
  confirm.value = {
    text: tr(`Remove ${nameOf(c)}? They won't be able to see or do anything for you.`, `${nameOf(c)}-কে সরাবেন? তিনি আর আপনার কিছু দেখতে বা করতে পারবেন না।`),
    run: () => call('care_remove', { caretakerUid: c.caretakerUid }),
  };
}
function askStop(c: CareLink) {
  confirm.value = {
    text: tr(`Stop being ${c.patientName}'s caregiver?`, `${c.patientName}-এর কেয়ারগিভার থেকে সরে যাবেন?`),
    run: () => call('care_remove', { patientUid: c.patientUid }),
  };
}
function openFor(c: CareLink) {
  setCareMode({ uid: c.patientUid, name: c.patientName });
  nav('/prescriptions');
}
const accept = (c: CareLink) => act(() => call('care_respond', { patientUid: c.patientUid, accept: true }), tr(`You're now ${c.patientName}'s caregiver`, `আপনি এখন ${c.patientName}-এর কেয়ারগিভার`));
const decline = (c: CareLink) => act(() => call('care_respond', { patientUid: c.patientUid, accept: false }));

function inviteSent() {
  inviting.value = false;
  toast(tr("Sent. They'll appear once they accept.", 'পাঠানো হয়েছে। তিনি রাজি হলে চালু হবে।'));
  q.refetch();
}
const deleteMember = computed(() => {
  const m = member.value;
  const data = d.value;
  if (m === 'new' || !m || !data) return undefined;
  return () => act(() => call('family_set', { family: data.family.filter((x) => x.id !== m.id) })).then((ok) => ok && (member.value = null));
});
function saveMember(m: FamilyMember) {
  const data = d.value!;
  const next = member.value === 'new' ? [...data.family, m] : data.family.map((x) => (x.id === m.id ? m : x));
  act(() => call('family_set', { family: next }), tr('Saved', 'সেভ হয়েছে')).then((ok) => {
    if (ok) member.value = null;
  });
}
function runConfirm() {
  const c = confirm.value;
  if (c) act(c.run).then(() => (confirm.value = null));
}
</script>

<template>
  <SubPageBar :title="title" :on-back="back" />
  <div class="form-page">
    <div class="form-inner acct-settings">
      <ErrorText :text="error" />
      <EmptyState v-if="s.ready && s.guest" icon="health_and_safety_filled" :title="tr('Log in to do this', 'এর জন্য লগইন করুন')">
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </EmptyState>
      <template v-else-if="!d">
        <ErrorBox v-if="q.error" :error="q.error.message" :on-retry="() => q.refetch()" />
        <SkeletonCards v-else :n="2" :h="180" />
      </template>
      <div v-else class="acct-scols">
        <div class="col gap-12">
          <section class="acct-scard pad">
            <h2 class="t-title-m">{{ tr('My caregivers', 'আমার কেয়ারগিভার') }}</h2>
            <p class="t-body-s muted">
              {{
                tr(
                  "Up to 2 people you trust — your emergency contacts. From their own phone they can see your prescriptions and reports, send them to a pharmacy or lab, pay for a report review and get a serial for you. They also get your notifications. They can't change your profile or delete your account. Remove them any time.",
                  'সর্বোচ্চ ২ জন বিশ্বস্ত মানুষ — আপনার জরুরি যোগাযোগ। তাঁরা নিজের ফোন থেকেই আপনার প্রেসক্রিপশন আর রিপোর্ট দেখতে, ফার্মেসি বা প্যাথলজিতে পাঠাতে, রিপোর্ট দেখানোর টাকা দিতে আর আপনার সিরিয়াল নিতে পারবেন; আপনার নোটিফিকেশনও পাবেন। প্রোফাইল বদলাতে বা অ্যাকাউন্ট মুছতে পারবেন না। যেকোনো সময় সরিয়ে দিতে পারবেন।',
                )
              }}
            </p>
            <CarePerson v-for="c in d.caretakers" :key="c.caretakerUid" icon="person_outline" :name="nameOf(c)" :sub="c.caretakerEmail">
              <template #tag><Tag :tone="c.status === 'active' ? 'good' : undefined">{{ c.status === 'active' ? tr('Active', 'চালু') : tr('Waiting for them', 'রাজি হওয়ার অপেক্ষায়') }}</Tag></template>
              <Action icon="close" @click="askRemoveCaretaker(c)">{{ tr('Remove', 'সরিয়ে দিন') }}</Action>
            </CarePerson>
            <Button v-if="d.caretakers.length < 2" variant="outline" icon="add" block @click="inviting = true">{{ tr('Add a caregiver', 'কেয়ারগিভার যোগ করুন') }}</Button>
          </section>
          <section v-if="d.caringFor.length" class="acct-scard pad">
            <h2 class="t-title-m">{{ tr('People I care for', 'যাঁদের দেখাশোনা করি') }}</h2>
            <CarePerson
              v-for="c in d.caringFor"
              :key="c.patientUid"
              icon="health_and_safety_filled"
              :name="c.patientName || '-'"
              :sub="c.status === 'active' ? tr('You can act for them', 'আপনি ওঁর হয়ে কাজ করতে পারেন') : tr('Wants you as their caregiver', 'আপনাকে কেয়ারগিভার করতে চান')"
            >
              <template v-if="c.status === 'active'">
                <Action primary icon="chevron_right" @click="openFor(c)">{{ tr('Open for them', 'ওঁর হয়ে খুলুন') }}</Action>
                <Action icon="close" @click="askStop(c)">{{ tr('Stop being caregiver', 'সরে যান') }}</Action>
              </template>
              <template v-else>
                <Action primary icon="check_circle" :busy="busy" @click="accept(c)">{{ tr('Accept', 'রাজি') }}</Action>
                <Action icon="close" :busy="busy" @click="decline(c)">{{ tr('Decline', 'না') }}</Action>
              </template>
            </CarePerson>
          </section>
        </div>
        <div class="col gap-12">
          <section class="acct-scard pad">
            <h2 class="t-title-m">{{ tr('Family members', 'পরিবারের সদস্য') }}</h2>
            <p class="t-body-s muted">
              {{
                tr(
                  "For family who don't have their own phone or account — elderly parents, children. Get a serial in their name, and add their prescriptions with the code and their mobile number.",
                  'যাঁদের নিজের ফোন বা অ্যাকাউন্ট নেই — বয়স্ক বাবা-মা, শিশু। তাঁদের নামে সিরিয়াল নিন; প্রেসক্রিপশনের কোড আর তাঁদের মোবাইল নম্বর দিয়ে প্রেসক্রিপশন যোগ করুন।',
                )
              }}
            </p>
            <CarePerson v-for="m in d.family" :key="m.id" icon="person_outline" :name="m.name" :sub="famSub(m)">
              <template #tag><Action icon="edit" @click="member = m">{{ tr('Edit', 'বদলান') }}</Action></template>
            </CarePerson>
            <Button v-if="d.family.length < 6" variant="outline" icon="add" block @click="member = 'new'">{{ tr('Add a family member', 'পরিবারের সদস্য যোগ করুন') }}</Button>
          </section>
        </div>
      </div>
    </div>
  </div>
  <InviteDialog v-if="inviting" @close="inviting = false" @sent="inviteSent" />
  <MemberDialog v-if="member && d" :existing="member === 'new' ? null : member" :busy="busy" :on-delete="deleteMember" @close="member = null" @save="saveMember" />
  <ConfirmDialog
    v-if="confirm"
    :title="tr('Are you sure?', 'নিশ্চিত?')"
    :text="confirm.text"
    :confirm="tr('Yes', 'হ্যাঁ')"
    danger
    :busy="busy"
    @close="() => !busy && (confirm = null)"
    @confirm="runConfirm"
  />
</template>
