<script setup lang="ts">
// QR-এর নিচে কোড লেখা — স্ক্যান না হলে দোকান হাতে লিখতে পারে; চাপলে কপি (ছাপায় শুধু লেখা)
import { toast } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { groupCode } from './rx';
import Qr from './Qr.vue';

const props = defineProps<{ text: string; code: string; size: number; print?: boolean }>();
const copy = () => {
  navigator.clipboard?.writeText(props.code).then(() => toast(tr('Code copied', 'কোড কপি হয়েছে'))).catch(() => {});
};
</script>

<template>
  <div class="col health-qr-box">
    <Qr :text="text" :size="size" />
    <span class="t-label-s muted">{{ tr('Code', 'কোড') }}</span>
    <span v-if="print" class="health-qr-code">{{ groupCode(code) }}</span>
    <button v-else type="button" class="health-qr-code" :title="tr('Copy the code', 'কোড কপি করুন')" :aria-label="tr('Code ', 'কোড ') + code" @click="copy">{{ groupCode(code) }}</button>
  </div>
</template>
