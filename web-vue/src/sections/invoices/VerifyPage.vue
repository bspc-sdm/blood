<script setup lang="ts">
// /v/:code — রসিদ যাচাই (পাবলিক, লগইন ছাড়া; invoice_verify, সার্ভারে সীমিত)। রসিদের QR এখানে আনে।
// শুধু: প্রতিষ্ঠান, ধরন, রসিদ নং, তারিখ, মোট, পরিশোধ/বাকি, লাইনের সংখ্যা, অবস্থা (বৈধ / বাতিল) — রোগী বা ক্রেতার কিছুই নয়।
// নিরাপত্তা পর্যালোচনা I1/I9/I10: নম্বর, তারিখ আর মোট বড় করে ("কাগজের সাথে মিলিয়ে নিন"), লাইনের সংখ্যা (যোগ করা লাইন ধরা পড়ে),
// ডেমো রসিদে বড় "ডেমো — আসল রসিদ নয়" (কখনো "বৈধ" নয়), না পেলে অফলাইনের ব্যাখ্যা, স্থগিত প্রতিষ্ঠান।
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Button, Icon, Loading, Tag } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { ServerError, call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { formatDateTime, formatDayOf } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { T } from './text';
import { invNo, kindIcon, kindText, money, parseVerify, type VerifyInfo } from './model';

usePageTitle(() => T.verifyTitle());
const route = useRoute();
const nav = useNav();
const clean = (s: string) => s.replace(/[^A-Za-z0-9]/g, '').slice(0, 20);
const code = computed(() => clean(String(route.params.code ?? '')));
const typed = ref('');
const q = useQ<VerifyInfo | null>(() => ({
  queryKey: ['invoice_verify', code.value],
  queryFn: () =>
    call<any>('invoice_verify', { code: code.value })
      .then(parseVerify)
      .catch((e) => {
        if (e instanceof ServerError && e.status === 404) return null;
        throw e;
      }),
  enabled: code.value.length >= 6,
  retry: false,
}));
const v = computed(() => q.data ?? null);
const tone = computed(() => (!v.value ? '' : v.value.demo || v.value.status === 'void' ? 'bad' : 'good'));
function go() {
  const c = clean(typed.value);
  if (c.length >= 6) nav(`/v/${c}`);
}
</script>

<template>
  <div class="acct-page">
    <div class="acct-narrow col gap-12 inv-verify">
      <h1 class="t-headline">{{ T.verifyTitle() }}</h1>
      <Loading v-if="code.length >= 6 && q.isPending" />
      <section v-else-if="v" :class="['card col gap-12 inv-verify-card', tone]" role="status" data-testid="verify-result">
        <div v-if="v.demo" class="inv-verify-demo" data-testid="verify-demo">{{ T.demoNotReal() }}</div>
        <div v-if="!v.demo" class="row gap-8">
          <Icon :name="v.status === 'void' ? 'warning' : 'verified'" :size="28" />
          <h2 class="t-title-l grow">{{ v.status === 'void' ? T.verifyVoid() : T.verifyValid() }}</h2>
          <Tag :tone="v.status === 'void' ? 'error' : 'good'" data-testid="verify-status">{{ v.status === 'void' ? T.voided() : T.valid() }}</Tag>
        </div>
        <div class="row gap-6 t-title-m"><Icon :name="kindIcon(v.kind)" :size="20" />{{ v.bizName }}</div>
        <span class="t-body-s muted">{{ kindText(v.kind) }}</span>
        <div class="inv-verify-big num">
          <div><span class="t-label-m muted">{{ T.receiptNo() }}</span><b data-testid="verify-no">{{ invNo(v.no) }}</b></div>
          <div><span class="t-label-m muted">{{ T.issued() }}</span><b>{{ formatDateTime(v.issuedAt) }}</b></div>
          <div><span class="t-label-m muted">{{ tr('Total', 'মোট') }}</span><b data-testid="verify-total">{{ money(v.total) }}</b></div>
        </div>
        <dl class="kv num">
          <dt>{{ T.paid() }}</dt><dd>{{ money(v.paid) }}</dd>
          <template v-if="v.due > 0"><dt>{{ T.due() }}</dt><dd class="c-error">{{ money(v.due) }}</dd></template>
        </dl>
        <span v-if="v.lines != null" class="t-body-m" data-testid="verify-lines">{{ T.verifyLines(v.lines) }}</span>
        <span v-if="v.status === 'void' && v.voidedAt" class="t-body-m c-error">{{ T.voidedOn(formatDayOf(v.voidedAt)) }}</span>
        <span v-if="v.suspended" class="t-body-m c-error">{{ T.verifySuspended() }}</span>
        <p class="t-body-m semi">{{ T.verifyMatch() }}</p>
        <p class="t-body-s muted">{{ T.verifyNote() }}</p>
      </section>
      <section v-else-if="code.length >= 6 && q.isError" class="card col gap-8" role="alert">
        <span class="t-body-m">{{ errorText(q.error) }}</span>
        <div><Button variant="tonal" icon="refresh" @click="q.refetch()">{{ tr('Try again', 'আবার চেষ্টা করুন') }}</Button></div>
      </section>
      <section v-else-if="code.length >= 6" class="card col gap-8 inv-verify-card bad" role="status" data-testid="verify-none">
        <div class="row gap-8"><Icon name="warning" :size="24" /><h2 class="t-title-m">{{ T.verifyNotFound() }}</h2></div>
        <span class="t-body-m num">{{ code }}</span>
        <p class="t-body-m">{{ T.verifyNotFoundWhy() }}</p>
      </section>
      <p v-else class="t-body-m semi" data-testid="verify-domain">{{ T.verifyOnlyDomain() }}</p>
      <form class="row gap-8" @submit.prevent="go">
        <input v-model="typed" class="input grow" maxlength="20" autocomplete="off" :aria-label="tr('Code', 'কোড')" placeholder="AB12CD34EF" />
        <Button type="submit" icon="search">{{ T.verifyTitle() }}</Button>
      </form>
    </div>
  </div>
</template>
