<script setup lang="ts">
// সেটিংস › গোপনীয়তা (backend/docs/CONSENT.md): স্বাস্থ্য তথ্যের সম্মতি — অবস্থা আর তারিখ, "পুরো লেখা দেখুন", "সম্মতি তুলে নিন"।
// তুলে নিলে নতুন স্বাস্থ্য তথ্য দেওয়া বন্ধ; আগের তথ্য মোছে না (নিজে মুছতে বা অ্যাকাউন্ট মুছতে পারেন — লিংক আছে)।
import { computed, onMounted, ref, watch } from 'vue';
import { Button, Dialog, ErrorText, Icon, Loading } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { tr, useLang } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { CONSENT_SCOPES, consent, loadConsent, openConsent, withdrawConsent } from '@/lib/consent';
import SLabel from '@/sections/account/SLabel.vue';

const nav = useNav();
const loading = ref(true);
const loadError = ref<string | null>(null);
const confirmOpen = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);

const st = computed(() => consent.status);
const anyGranted = computed(() => !!st.value && CONSENT_SCOPES.some((k) => ['granted', 'outdated'].includes(st.value!.scopes[k]?.state)));

async function load() {
  loading.value = true;
  loadError.value = null;
  try {
    await loadConsent();
  } catch (e) {
    loadError.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
// সম্মতির লেখা সার্ভার দেয় ডাকার সময়ের ভাষায় — ভাষা বদলালে নতুন করে (নইলে বাংলায় ফিরলেও ইংরেজি লেখা থেকে যেত)
const lang = useLang();
watch(lang, () => loadConsent().catch(() => {}));

const SOURCE: Record<string, () => string> = {
  app: () => tr('in the app', 'অ্যাপে'),
  web: () => tr('on the web', 'ওয়েবে'),
  desktop: () => tr('on the desktop app', 'ডেস্কটপ অ্যাপে'), // bn-ok
};
function stateLine(k: string): { icon: string; text: string; tone: string } {
  const s = st.value?.scopes[k];
  const when = s?.at ? formatDateTime(s.at) : '';
  const where = s?.source ? SOURCE[s.source]?.() ?? '' : '';
  switch (s?.state) {
    case 'granted':
      return { icon: 'check_circle', tone: 'good', text: tr(`Given · ${when} ${where}`, `দেওয়া আছে · ${when} ${where}`) }; // bn-ok
    case 'outdated':
      return { icon: 'warning', tone: 'warn', text: tr('The text has changed — please read it and agree again', 'লেখা বদলেছে — আবার পড়ে সম্মতি দিন') }; // bn-ok
    case 'withdrawn':
      return { icon: 'info', tone: 'muted', text: tr(`Withdrawn · ${when}`, `তুলে নিয়েছেন · ${when}`) }; // bn-ok
    default:
      return { icon: 'info', tone: 'muted', text: tr('Not given', 'দেওয়া হয়নি') };
  }
}

async function viewFull() {
  await openConsent('view');
}

async function withdraw() {
  busy.value = true;
  err.value = null;
  try {
    await withdrawConsent('all');
    confirmOpen.value = false;
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function toDelete() {
  confirmOpen.value = false;
  nav('/delete-account');
}
</script>

<template>
  <section class="acct-scard" data-testid="consent-settings">
    <SLabel icon="privacy_tip">{{ tr('Privacy', 'গোপনীয়তা') }}</SLabel> <!-- bn-ok -->
    <div class="acct-sbody consent-settings">
      <span class="t-title-s">{{ tr('Consent for health information', 'স্বাস্থ্য তথ্যের সম্মতি') }}</span> <!-- bn-ok -->
      <Loading v-if="loading && !st" />
      <ErrorText v-else-if="loadError && !st" :text="loadError" />
      <template v-else-if="st">
        <ul class="consent-states" role="list">
          <li v-for="k in CONSENT_SCOPES" :key="k" class="consent-state">
            <Icon :name="stateLine(k).icon" :size="20" :class="`tone-${stateLine(k).tone}`" />
            <span class="col gap-2">
              <span class="t-body-m">{{ st.text.scopes[k] }}</span>
              <span class="t-body-s muted" :data-testid="`consent-state-${k}`">{{ stateLine(k).text }}</span>
            </span>
          </li>
        </ul>
        <div class="row wrap">
          <Button variant="outline" small data-testid="consent-view" @click="viewFull">{{ tr('See the full text', 'পুরো লেখা দেখুন') }}</Button> <!-- bn-ok -->
          <Button v-if="!st.current" small data-testid="consent-give" @click="viewFull">{{ tr('Give consent', 'সম্মতি দিন') }}</Button>
          <Button v-if="anyGranted" variant="danger-text" small data-testid="consent-withdraw" @click="confirmOpen = true">{{ st.text.withdrawButton }}</Button>
        </div>
      </template>
    </div>
    <Dialog v-if="confirmOpen && st" size="small" :title="st.text.withdrawTitle" @close="!busy && (confirmOpen = false)">
      <p class="t-body-m">{{ st.text.withdrawBody }}</p>
      <button type="button" class="consent-link t-label-l link-btn" @click="toDelete">{{ st.text.deleteAccountLabel || tr('Delete account', 'অ্যাকাউন্ট মুছুন') }}</button>
      <ErrorText :text="err" />
      <template #actions>
        <Button variant="text" :disabled="busy" @click="confirmOpen = false">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button variant="danger" :busy="busy" data-testid="consent-withdraw-confirm" @click="withdraw">{{ st.text.withdrawButton }}</Button>
      </template>
    </Dialog>
  </section>
</template>
