<script setup lang="ts">
// /work — "আমার কাজ"-এর প্রথম পাতা "আজকের কাজ" (mywork CONTRACT §১), উপর থেকে নিচে:
// ১. ভূমিকার চিপ (একাধিক থাকলে): সব · চেম্বার · ফার্মেসি · ল্যাব · ব্লাড ব্যাংক · অ্যাম্বুলেন্স — বাছাটা মনে থাকে
// ২. প্রতিটা ব্যবসার অবস্থা: খোলা / বিরতি / বন্ধ (অ্যাম্বুলেন্স: খালি আছি / ট্রিপে আছি; ব্লাড ব্যাংক: স্টক কবে)
// ৩. আজকের সংখ্যা (চাপলে সেই সারি বাছা অবস্থায়) — নতুন/অসম্পূর্ণ হলে তার বদলে "শুরু করতে যা বাকি"
// ৪. "এখনই দেখুন" — কাজের সারি, একটাই বোতাম
// ৫. "রোগীরা আপনাকে যেভাবে দেখেন" — এক লাইন করে, "দেখুন"
// ৬. নিচে (বড় পর্দায় ডানে) কাজের পাতাগুলো আর "আরেকটা ব্যবসা যোগ করুন"
// কোনো ভূমিকা নেই (বা অতিথি): "সেবা দিন"। তথ্য: GET /api/work/today (না থাকলে আগের কাজগুলো থেকে — today/model.ts)।
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { useQueryClient } from '@tanstack/vue-query';
import { Button, ErrorText, Icon, PillTabs, SkeletonCards } from '@/ui/kit';
import Workspace from '@/shell/Workspace.vue';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { num, tr } from '@/lib/i18n';
import { errorText } from '@/lib/api';
import { useNav } from '@/lib/nav';
import {
  ambulanceDocs, badgeIcon, badgeLabel, bloodBankDocs, businessIcon, labDocs, myBusinessTitle, pharmacyDocs, statusCounts, useAfterChange, MAX_AMBULANCES, MAX_BLOODBANKS,
  type Ambulance, type BloodBank, type Lab, type Pharmacy,
} from './shared';
import WorkGate from './WorkGate.vue';
import DocsNeededNotice from './DocsNeededNotice.vue';
import { T as DT } from '@/offline/text';
import { invFeature } from '../invoices/features';
import { isDesktop } from '../invoices/offline';
import { writesRx } from '@/lib/practice';
import { practitionerLabel } from '@/lib/titleGuard';
import { T as PT } from '../pay/text';
import { useMyPayBiz } from '../pay/biz/bizlist';
import { isChamberType } from '../appointment/model';
import { TT } from './today/text';
import { ROLES, roleIcon, roleLabel, useRoleChoice, useWorkToday, type Role, type TodayBiz } from './today/model';
import RoleBlock from './today/RoleBlock.vue';
import TaskList from './today/TaskList.vue';
import PreviewLines from './today/PreviewLines.vue';
import ServeIntro from './today/ServeIntro.vue';

const s = useSession();
const nav = useNav();
const qc = useQueryClient();
const amb = computed(() => s.work.ambulances as Ambulance[]);
const ph = computed(() => s.work.pharmacy as Pharmacy | null);
const bbs = computed(() => s.work.bloodBanks as BloodBank[]);
const lab = computed(() => s.work.lab as Lab | null);
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const after = useAfterChange();
// বড় পর্দায় "এখনই দেখুন" ডানের ভাগের মাথায় (প্রথম পর্দাতেই দেখা যায়); ফোনে সংখ্যার নিচে
const wide = useMedia('(min-width: 1100px)');

const hasBusiness = computed(() => amb.value.length > 0 || !!ph.value || bbs.value.length > 0 || !!lab.value);
const payBiz = useMyPayBiz();
const desktopLink = computed(() => invFeature('desktop') && !isDesktop() && (!!ph.value || !!lab.value || s.work.professional === 'doctor' || s.work.assisting.length > 0));
const empty = computed(() => !hasBusiness.value && !s.work.professional && !s.work.assisting.length);
const title = computed(() => (s.guest || (ready.value && empty.value) ? TT.serveTab() : TT.title()));

// ভূমিকা — সেশনের তথ্য থেকেই (সার্ভারের আজকের তথ্য আসার আগেই চিপ আর অংশের জায়গা ঠিক থাকে)
const roles = computed<Role[]>(() => {
  const w = s.work;
  const has: Record<Role, boolean> = {
    chamber: isChamberType(w.professional) || w.assisting.length > 0,
    homecare: w.professional === 'nurse',
    pharmacy: !!w.pharmacy,
    lab: !!w.lab,
    bloodbank: w.bloodBanks.length > 0,
    ambulance: w.ambulances.length > 0,
  };
  return ROLES.filter((r) => has[r]);
});
const choice = useRoleChoice();
const role = computed<Role | 'all'>(() => (choice.value.value !== 'all' && roles.value.includes(choice.value.value) ? choice.value.value : 'all'));
const shownRoles = computed(() => (role.value === 'all' ? roles.value : [role.value]));

const today = useWorkToday();
const byRole = computed(() => {
  const m = new Map<Role, TodayBiz[]>();
  for (const b of today.data?.items ?? []) m.set(b.kind, [...(m.get(b.kind) ?? []), b]);
  return m;
});
const tasksOf = (r: Role) => (byRole.value.get(r) ?? []).flatMap((b) => b.tasks);
const tasks = computed(() => shownRoles.value.flatMap(tasksOf));
const chipTabs = computed(() => [
  { key: 'all', label: TT.all() },
  ...roles.value.map((r) => ({ key: r, label: roleLabel(r), icon: roleIcon(r), count: tasksOf(r).length })),
]);
async function changed() {
  await Promise.all([qc.invalidateQueries({ queryKey: ['work_today'] }), qc.invalidateQueries({ queryKey: ['chamber_days'] }), after()]);
}

const needs = (x: { docsMissing?: boolean; docsRequested?: boolean }) => !!(x.docsMissing || x.docsRequested);
const canAdd = computed(() => amb.value.length < MAX_AMBULANCES || !ph.value || bbs.value.length < MAX_BLOODBANKS || !lab.value);
const ambSub = computed(() => {
  const a = amb.value;
  return [
    tr(`${a.length} in total`, `${num(a.length)}টা`),
    ...(a.some((x) => x.status === 'approved') ? [tr(`${a.filter((x) => x.status === 'approved' && x.available).length} free`, `${num(a.filter((x) => x.status === 'approved' && x.available).length)}টা খালি`)] : []),
    ...statusCounts(a.map((x) => x.status)),
  ].join(' · ');
});
const bbSub = computed(() => [bbs.value.length === 1 ? bbs.value[0].name : tr(`${bbs.value.length} blood banks`, `${num(bbs.value.length)}টা`), ...statusCounts(bbs.value.map((b) => b.status))].join(' · '));
const pages = computed(() => {
  const w = s.work;
  const out: { key: string; icon: string; title: string; sub: string; to: string; testid?: string }[] = [];
  if (w.professional)
    out.push({
      key: 'pro',
      icon: badgeIcon(w.professional),
      title: w.professional === 'nurse' ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার'),
      sub: w.professional === 'nurse' ? badgeLabel(w.professional) : (practitionerLabel(s.me?.badge) ?? badgeLabel(w.professional)) + tr(' · bookings, patients, setup', ' · বুকিং, রোগী, সেটআপ'),
      to: '/work/pro',
    });
  if (writesRx(w.professional) || w.assisting.length)
    out.push({
      key: 'rx',
      icon: 'edit_note',
      title: tr('Prescriptions', 'প্রেসক্রিপশন'),
      sub: writesRx(w.professional) ? tr('Patients, write prescriptions, team, report reviews', 'রোগী, প্রেসক্রিপশন লেখা, টিম, রিপোর্ট দেখানো') : tr('Doctor\'s assistant · ', 'ডাক্তারের সহকারী · ') + w.assisting.map((d) => d.name).join(', '),
      to: '/work/rx',
    });
  if (ph.value) out.push({ key: 'ph', icon: businessIcon('pharmacy'), title: myBusinessTitle('pharmacy'), sub: ph.value.name, to: '/work/pharmacy' });
  if (lab.value) out.push({ key: 'lab', icon: businessIcon('lab'), title: myBusinessTitle('lab'), sub: lab.value.name, to: '/work/lab' });
  if (bbs.value.length) out.push({ key: 'bb', icon: businessIcon('bloodbank'), title: myBusinessTitle('bloodbank'), sub: bbSub.value, to: '/work/bloodbank' });
  if (amb.value.length) out.push({ key: 'amb', icon: businessIcon('ambulance'), title: myBusinessTitle('ambulance'), sub: ambSub.value, to: '/work/ambulance' });
  if (payBiz.value.length) out.push({ key: 'pay', icon: 'payments', title: PT.bizTitle(), sub: PT.methodsIntro(), to: '/work/payments', testid: 'pay-biz-link' });
  if (desktopLink.value) out.push({ key: 'desk', icon: 'storefront', title: DT.dlTitle(), sub: DT.dlIntro(), to: '/desktop-app', testid: 'desktop-app-link' });
  return out;
});
</script>

<template>
  <Workspace v-if="s.ready && s.guest" :title="title"><ServeIntro /></Workspace>
  <Workspace v-else-if="!ready" :title="title">
    <!-- কঙ্কাল (ঘুরন্ত চাকা নয়) — কাজ এলে একই জায়গায় বসে; ভুল হলে "আবার চেষ্টা করুন" -->
    <WorkGate v-if="s.workError" />
    <div v-else class="td-skel" data-testid="work-skeleton"><SkeletonCards :n="3" :h="120" /></div>
  </Workspace>
  <Workspace v-else-if="empty" :title="title"><ServeIntro /></Workspace>
  <Workspace v-else :title="title">
    <div class="td-page" data-testid="today-home">
      <div v-if="roles.length > 1" class="td-chips">
        <PillTabs nearest :label="TT.rolesLabel()" :tabs="chipTabs" :value="role" @change="(k) => choice.set(k as Role | 'all')" />
      </div>
      <div class="td-layout">
        <div class="td-main">
          <div class="td-blocks">
            <template v-if="today.isLoading && !today.data">
              <SkeletonCards v-for="r in shownRoles" :key="r" :n="1" :h="150" />
            </template>
            <template v-else>
              <RoleBlock v-for="r in shownRoles.filter((x) => byRole.get(x)?.length)" :key="r" :role="r" :bizs="byRole.get(r)!" @changed="changed" />
            </template>
          </div>
          <div v-if="today.error" class="row wrap gap-8">
            <ErrorText :text="TT.error() + ' — ' + errorText(today.error)" />
            <Button variant="text" small @click="today.refetch()">{{ tr('Try again', 'আবার চেষ্টা করুন') }}</Button>
          </div>
          <TaskList v-if="today.data && !wide" :tasks="tasks" :show-role="shownRoles.length > 1" />

          <div v-if="role === 'all' || role === 'ambulance' || role === 'pharmacy' || role === 'bloodbank' || role === 'lab'" class="col gap-8">
            <template v-if="role === 'all' || role === 'ambulance'">
              <DocsNeededNotice v-for="a in amb.filter(needs)" :key="'a' + a.id" kind="ambulance" :id="a.id" :specs="ambulanceDocs()" :item="a" :title="`${roleLabel('ambulance')} · ${a.name} · ${a.vehicleNo}`" @done="after" />
            </template>
            <DocsNeededNotice v-if="ph && needs(ph) && (role === 'all' || role === 'pharmacy')" key="ph" kind="pharmacy" :id="ph.id" :specs="pharmacyDocs()" :item="ph" :title="`${roleLabel('pharmacy')} · ${ph.name}`" @done="after" />
            <template v-if="role === 'all' || role === 'bloodbank'">
              <DocsNeededNotice v-for="b in bbs.filter(needs)" :key="'b' + b.id" kind="bloodbank" :id="b.id" :specs="bloodBankDocs(b.kind)" :item="b" :title="`${roleLabel('bloodbank')} · ${b.name}`" @done="after" />
            </template>
            <DocsNeededNotice v-if="lab && needs(lab) && (role === 'all' || role === 'lab')" key="lab" kind="lab" :id="lab.id" :specs="labDocs()" :item="lab" :title="`${roleLabel('lab')} · ${lab.name}`" @done="after" />
          </div>
        </div>

        <aside class="td-side">
          <TaskList v-if="today.data && wide" :tasks="tasks" :show-role="shownRoles.length > 1" />
          <PreviewLines :roles="shownRoles" />
          <section class="td-pages" data-testid="work-pages">
            <h2 class="t-title-s">{{ TT.pages() }}</h2>
            <ul class="col">
              <li v-for="p in pages" :key="p.key">
                <RouterLink :to="p.to" class="td-page-row" :data-testid="p.testid">
                  <span class="work-ic"><Icon :name="p.icon" /></span>
                  <span class="col gap-2 grow" :style="{ minWidth: 0 }">
                    <span class="t-title-s">{{ p.title }}</span>
                    <span class="t-body-s muted clamp2">{{ p.sub }}</span>
                  </span>
                  <Icon name="chevron_right" class="work-chev" />
                </RouterLink>
              </li>
            </ul>
            <Button v-if="canAdd" variant="outline" icon="add" @click="nav('/work/add')">{{ hasBusiness ? tr('Add another business', 'আরেকটা ব্যবসা যোগ করুন') : tr('Add business', 'ব্যবসা যোগ করুন') }}</Button>
          </section>
        </aside>
      </div>
    </div>
  </Workspace>
</template>
