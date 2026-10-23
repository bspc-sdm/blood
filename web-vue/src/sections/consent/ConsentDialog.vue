<script setup lang="ts">
// স্বাস্থ্য তথ্যের সম্মতির পর্দা (backend/docs/CONSENT.md) — সব লেখা সার্ভার থেকে (একটাই ভার্সন করা ফাইল)।
// মোবাইলে নিচের শিট: কী, কেন, কোথায়, আর কে দেখে, কতদিন, কীভাবে তুলে নেবেন; তারপর দুটো সম্মতি আর "রাজি আছি" / "এখন না"।
// readOnly: সেটিংসের "পুরো লেখা দেখুন" (সম্মতি আগেই আছে) — শুধু পড়া।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, Icon } from '@/ui/kit';
import { errorText, ServerError } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { CONSENT_SCOPES, grantConsent, type ConsentStatus } from '@/lib/consent';

const props = defineProps<{ status: ConsentStatus; readOnly?: boolean }>();
const emit = defineEmits<{ agree: []; later: [] }>();

const t = computed(() => props.status.text);
const busy = ref(false);
const err = ref<string | null>(null);
const changed = ref(false);

/** প্রতিটা অংশের আইকন (চাবি সার্ভারের লেখায়) */
const ICON: Record<string, string> = {
  what: 'medical_services',
  why: 'info',
  where: 'language',
  who: 'person_outline',
  how_long: 'schedule',
  withdraw: 'history',
};

async function agree() {
  busy.value = true;
  err.value = null;
  changed.value = false;
  try {
    await grantConsent(CONSENT_SCOPES);
    emit('agree');
  } catch (e) {
    // লেখা বদলেছে: নতুন লেখা এখন দেখানো হচ্ছে — আবার পড়ে চাপুন
    if (e instanceof ServerError && e.code === 'consent_outdated') changed.value = true;
    else err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="t.title" @close="!busy && emit('later')">
    <div class="consent" data-testid="consent-dialog">
      <p class="t-body-m">{{ t.intro }}</p>
      <ul class="consent-points" role="list">
        <li v-for="s in t.sections" :key="s.key" class="consent-point">
          <Icon :name="ICON[s.key] || 'info'" :size="22" />
          <div class="col gap-2">
            <h3 class="t-title-s">{{ s.heading }}</h3>
            <p class="t-body-m muted">{{ s.body }}</p>
            <a v-if="s.linkUrl" class="consent-link t-label-l" :href="s.linkUrl" target="_blank" rel="noopener noreferrer">{{ s.linkLabel || s.linkUrl }}</a>
          </div>
        </li>
      </ul>
      <section v-if="!readOnly" class="consent-scopes" :aria-label="tr('What you agree to', 'যাতে সম্মতি দিচ্ছেন')"> <!-- bn-ok -->
        <p v-for="k in CONSENT_SCOPES" :key="k" class="consent-scope t-body-m">
          <Icon name="check_circle" :size="20" />
          <span>{{ t.scopes[k] }}</span>
        </p>
        <p class="t-body-s muted">{{ t.agreeNote }}</p>
      </section>
      <a class="consent-link t-label-l" :href="t.policyUrl" target="_blank" rel="noopener noreferrer">{{ t.policyLabel || t.policyUrl }}</a>
      <p v-if="changed" class="t-body-s consent-changed" role="status">{{ tr('The text has just changed — please read it again.', 'লেখাটা এইমাত্র বদলেছে — আবার পড়ে দেখুন।') }}</p> <!-- bn-ok -->
      <ErrorText :text="err" />
    </div>
    <template #actions>
      <template v-if="readOnly">
        <Button @click="emit('later')">{{ tr('Close', 'বন্ধ') }}</Button>
      </template>
      <template v-else>
        <Button variant="text" :disabled="busy" @click="emit('later')">{{ t.later }}</Button>
        <Button :busy="busy" data-testid="consent-agree" @click="agree">{{ t.agree }}</Button>
      </template>
    </template>
  </Dialog>
</template>
