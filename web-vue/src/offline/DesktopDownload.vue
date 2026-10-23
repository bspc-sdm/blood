<script setup lang="ts">
// ডেস্কটপ অ্যাপ নামানোর পাতা (/desktop-app) — ফিচার সুইচ `desktop` চালু থাকলে তবেই লিংক (flags CONTRACT)।
// ফাইল: https://lifolo.com/download/desktop/lifolo-desktop.exe (সর্বশেষ), ভার্সন আর SHA-256 desktop.json থেকে।
import { onMounted, ref } from 'vue';
import FormPage from '@/shell/FormPage.vue';
import { num } from '@/lib/i18n';
import { desktopFeatureOn } from './feature';
import { T } from './text';

const BASE = 'https://lifolo.com/download/desktop';
const info = ref<{ version?: string; sha256?: string; url?: string } | null>(null);

onMounted(async () => {
  try {
    const r = await fetch('/download/desktop/desktop.json', { cache: 'no-store' });
    if (r.ok) info.value = await r.json();
  } catch {
    /* লিংক তবুও চলে */
  }
});
</script>

<template>
  <FormPage :title="T.dlTitle()">
    <div v-if="!desktopFeatureOn()" class="col gap-8">
      <p class="t-body-l">{{ T.soon() }}</p>
    </div>
    <div v-else class="col gap-16">
      <p class="t-body-l">{{ T.dlIntro() }}</p>
      <div>
        <a class="btn" :href="info?.url || `${BASE}/lifolo-desktop.exe`" download>{{ T.dlButton() }}</a>
      </div>
      <p class="t-body-s muted">{{ T.dlNeeds() }}<template v-if="info?.version"> · {{ T.version(info.version) }}</template></p>
      <p class="t-body-m">{{ T.dlOnly() }}</p>
      <section class="col gap-8">
        <h2 class="t-title-m">{{ T.dlSmartTitle() }}</h2>
        <p class="t-body-m">{{ num(1) }}. {{ T.dlSmart1() }}</p>
        <p class="t-body-m">{{ num(2) }}. {{ T.dlSmart2() }}</p>
      </section>
      <p v-if="info?.sha256" class="t-body-s muted lfd-sha">{{ T.dlSha() }}: {{ info.sha256 }}</p>
    </div>
  </FormPage>
</template>

<style scoped>
.lfd-sha {
  word-break: break-all;
  font-family: ui-monospace, monospace;
}
</style>
