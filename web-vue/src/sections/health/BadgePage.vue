<script setup lang="ts">
// পেশাদার ব্যাজ: আবেদন বা অনুমোদিত ব্যাজের তথ্য সংশোধন, কাগজের ছবিসহ — অ্যাপের BadgeApplyDialog, Verify.kt, Profile.kt-এর ব্যাজ সারি
import { computed, ref } from 'vue';
import { Button, EmptyState, ErrorText, Icon, IconLine, InfoText, Loading, Tag, TextInput } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { badgeChoices, badgeIcon, badgeLabel, goBack, useMe } from './common';
import { SYSTEMS, T as PT, isSystem, regBody, regPlaceholder, systemLabel, systemsOn, type MedSystem } from '@/lib/practice';
import { badgeDocs, missingDocs, statusLine } from './badge';
import DocPhotosField from './DocPhotosField.vue';
import DocsNeeded from './DocsNeeded.vue';
import { TG, titleError, titleHint } from '@/lib/titleGuard';

const nav = useNav();
const s = useSession();
const me = useMe();
const back = () => goBack(nav, '/profile');
const cur = computed(() => me.value?.badge ?? null);
const correction = computed(() => cur.value?.status === 'approved');
const type = ref<string | null>(null);
const regNo = ref<string | null>(null);
const workplace = ref<string | null>(null);
const docs = ref<Record<string, string>>({});
const busy = ref(false);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const t = computed(() => type.value ?? cur.value?.type ?? 'doctor');
// চিকিৎসা পদ্ধতি (practice_systems, শুধু ডাক্তার): নিবন্ধনের প্রতিষ্ঠান আর ডিগ্রির ইঙ্গিত; অনুমোদনের পর বদলালে আবার যাচাই
const sysPick = ref<MedSystem | null>(null);
const showSys = computed(() => systemsOn() && t.value === 'doctor');
const curSys = computed<MedSystem>(() => (isSystem(cur.value?.system) ? cur.value.system : 'allopathy'));
const sys = computed<MedSystem>(() => sysPick.value ?? curSys.value);
const sysChanged = computed(() => correction.value && showSys.value && sys.value !== curSys.value);
const docSpecs = computed(() => badgeDocs(t.value, showSys.value ? sys.value : null));
const typeHint = computed(() => (t.value === 'physio' ? PT.physioDocsHint() : t.value === 'psychologist' ? PT.psychDocsHint() : null));
const reg = computed(() => regNo.value ?? cur.value?.regNo ?? '');
const work = computed(() => workplace.value ?? cur.value?.workplace ?? '');
const title = computed(() => (correction.value ? tr('Correct badge details', 'ব্যাজের তথ্য সংশোধন') : tr('Professional badge', 'পেশাদার ব্যাজ')));
const locked = computed(() => cur.value?.status === 'pending' || !!cur.value?.changePending);
const st = computed(() => (cur.value ? statusLine(cur.value) : null));
const reason = computed(() => (cur.value && ['rejected', 'suspended'].includes(cur.value.status) ? String(cur.value.statusReason || '') : ''));
const docsMissing = computed(() => !!cur.value?.docsMissing && cur.value?.status === 'pending');
const docsRequested = computed(() => !!cur.value?.docsRequested);

// "ডা." পদবির নিয়ম (title_guard): ব্যাজ প্রোফাইলের নাম নেয় — MBBS/BDS ছাড়া নামের আগে "ডা." থাকলে আগে প্রোফাইলে ঠিক করা
const badgeSys = computed(() => (t.value === 'doctor' ? (showSys.value ? sys.value : (cur.value?.system ?? 'allopathy')) : null));
const profName = computed(() => s.profile?.name ?? '');
const nameErr = computed(() => titleError(profName.value, t.value, badgeSys.value));
const nameHint = computed(() => titleHint(t.value, badgeSys.value));

async function submit() {
  if (nameErr.value) {
    err.value = nameErr.value;
    return;
  }
  if (!correction.value || sysChanged.value) {
    const m = missingDocs(docSpecs.value, docs.value);
    if (m) {
      err.value = m;
      return;
    }
  }
  busy.value = true;
  err.value = null;
  try {
    await call('badge_apply', { type: t.value, regNo: reg.value.trim(), workplace: work.value.trim(), docs: docs.value, ...(showSys.value ? { system: sys.value } : {}) });
    sysPick.value = null;
    docs.value = {};
    info.value = correction.value ? tr('Sent. An admin will check it.', 'পাঠানো হয়েছে। অ্যাডমিন যাচাই করবেন।') : tr('Applied. An admin will verify it.', 'আবেদন হয়েছে। অ্যাডমিন যাচাই করবেন।');
    await s.reloadMe();
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <FormPage v-if="s.guest" :title="tr('Professional badge', 'পেশাদার ব্যাজ')" :on-back="back">
    <EmptyState icon="workspace_premium" :title="tr('Professional badge', 'পেশাদার ব্যাজ')" :text="tr('For doctors, nurses and health experts', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞদের জন্য')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </FormPage>
  <FormPage v-else-if="!me" :title="title" :on-back="back"><Loading /></FormPage>
  <FormPage v-else :title="title" :on-back="back">
    <div v-if="cur && st" class="card col">
      <div class="row">
        <span class="avatar" :style="{ width: '40px', height: '40px' }"><Icon :name="badgeIcon(cur.type)" :size="20" /></span>
        <div class="col gap-2 grow">
          <span class="t-title-s">{{ tr('Professional badge', 'পেশাদার ব্যাজ') }}</span>
          <span><Tag :tone="st.tone">{{ st.text }}</Tag></span>
        </div>
      </div>
      <div v-if="cur.regNo || cur.workplace" class="col gap-4">
        <IconLine v-if="cur.regNo" icon="badge">{{ (systemsOn() && cur.type === 'doctor' ? regBody(curSys) + ' ' : '') + cur.regNo }}</IconLine>
        <IconLine v-if="cur.workplace" icon="local_hospital">{{ cur.workplace }}</IconLine>
      </div>
      <IconLine v-if="reason" icon="info">{{ tr('Reason: ', 'কারণ: ') + reason }}</IconLine>
    </div>
    <DocsNeeded v-if="cur && (docsMissing || docsRequested) && s.uid" :uid="s.uid" :type="cur.type" :system="systemsOn() ? cur.system : null" :requested="docsRequested" :note="String(cur.docsRequestNote || '')" @done="s.reloadMe()" />
    <InfoText :text="info" />
    <template v-if="!locked">
      <p v-if="correction" class="t-body-s muted">
        {{ tr(
          'Fix your registration number or workplace. To fix your name, change it in Edit profile first, then send this. You stay listed while an admin checks.',
          'রেজিস্ট্রেশন নম্বর বা কর্মস্থল ঠিক করুন। নাম ঠিক করতে আগে "প্রোফাইল এডিট"-এ নাম বদলে তারপর এটা পাঠান। অ্যাডমিন দেখা পর্যন্ত আপনি আগের মতোই তালিকায় থাকবেন।',
        ) }}
      </p>
      <div class="field">
        <span class="label">{{ tr('Profession', 'পেশা') }}</span>
        <div class="chips health-chips" role="group" :aria-label="tr('Profession', 'পেশা')">
          <button v-for="b in badgeChoices()" :key="b" type="button" class="chip lead" :aria-pressed="t === b" :disabled="correction && t !== b" @click="type = b">
            <Icon :name="badgeIcon(b)" />{{ badgeLabel(b) }}
          </button>
        </div>
      </div>
      <p v-if="typeHint" class="t-body-s muted" data-testid="type-hint">{{ typeHint }}</p>
      <div v-if="showSys" class="field" data-testid="badge-system">
        <span class="label">{{ PT.system() }}</span>
        <div class="chips" role="group" :aria-label="PT.system()">
          <button v-for="x in SYSTEMS" :key="x" type="button" class="chip" :aria-pressed="sys === x" @click="sysPick = x">{{ systemLabel(x) }}</button>
        </div>
        <span class="t-body-s muted">{{ PT.bodyHint(sys) }}</span>
        <InfoText v-if="sysChanged" :text="PT.reverify()" />
      </div>
      <template v-if="nameHint">
        <TextInput :label="tr('Name', 'নাম')" :model-value="profName" disabled :hint="nameHint" :error="nameErr" data-testid="badge-name" />
        <div v-if="nameErr" class="row"><Button variant="text" icon="edit" @click="nav('/profile/edit')">{{ TG.fixInProfile() }}</Button></div>
      </template>
      <TextInput
        :label="showSys && sys !== 'allopathy' ? PT.regNoFor(sys) : tr('Registration / licence no.', 'রেজিস্ট্রেশন / লাইসেন্স নম্বর')"
        :model-value="reg"
        maxlength="60"
        :placeholder="t === 'doctor' ? regPlaceholder(showSys ? sys : 'allopathy') : ''"
        @update:model-value="(v: string) => (regNo = v)"
      />
      <TextInput :label="tr('Workplace', 'কর্মস্থল')" :model-value="work" maxlength="120" @update:model-value="(v: string) => (workplace = v)" />
      <DocPhotosField
        :specs="docSpecs"
        :photos="docs"
        :note="correction && !sysChanged ? tr('Add a photo if your registration number changed.', 'রেজিস্ট্রেশন নম্বর বদলালে সনদের ছবি দিন।') : null"
        @change="(p) => (docs = p)"
      />
      <p class="t-body-s muted">{{ tr('An admin will verify this before the badge appears.', 'অ্যাডমিন যাচাই করার পর ব্যাজ দেখা যাবে।') }}</p>
      <ErrorText :text="err" />
      <div class="form-actions">
        <Button variant="text" :disabled="busy" @click="back">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button :busy="busy" @click="submit">{{ correction ? tr('Send', 'পাঠান') : tr('Apply', 'আবেদন করুন') }}</Button>
      </div>
    </template>
  </FormPage>
</template>
