<script setup lang="ts">
// সম্মতির পর্দা যেকোনো পাতার ওপরে (App.vue-তে একবার): সার্ভারের 428 consent_required বা স্বাস্থ্যের পাতা খোলায়।
// "রাজি আছি" → api.ts আটকে থাকা কাজটা আবার পাঠায়; "এখন না" → কাজটা হয় না, জরুরি সেবা আগের মতোই।
import { answerConsent, consent, initConsent } from '@/lib/consent';
import ConsentDialog from './ConsentDialog.vue';

initConsent();
</script>

<template>
  <ConsentDialog
    v-if="consent.prompt && consent.status"
    :status="consent.status"
    :read-only="consent.prompt.reason === 'view' && consent.status.current"
    @agree="answerConsent(true)"
    @later="answerConsent(false)"
  />
</template>
