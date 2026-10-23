<script setup lang="ts">
// রসিদের কাগজ (INVOICES.md "Receipt look", সব ক্লায়েন্টে একই): উপরে প্রতিষ্ঠান, ঠিকানা, ফোন, ছোট Lifolo লোগো;
// তারপর লাইন, মোট, পরিশোধ/বাকি/মাধ্যম; নিচে যাচাইয়ের QR আর "lifolo.com/v/<code> — রসিদ যাচাই করুন"। বাতিল হলে "বাতিল" ছাপ।
// paper: A4 (ছক) বা থার্মাল ৮০/৫৮ মিমি (সরু, এক কলাম)। digits: বাংলা মূল, ইংরেজি বাছা যায়।
// নিরাপত্তা পর্যালোচনা (docs/test-plan/INVOICES-SECURITY-REVIEW.md): ক্রেতার ফোন কখনো ছাপা হয় না (I6, শুধু নাম);
// নিচে "টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়" (I15); অফলাইন রসিদে QR-এর নিচে "ইন্টারনেট এলে যাচাই হবে" (I1); ডেমো রসিদে জলছাপ (I9)।
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import Qr from '@/sections/health/Qr.vue';
import { T } from './text';
import { digitsOf, isDemo, isPending, kindText, methodText, methodsOf, moneyIn, verifyShort, type Invoice, type Paper } from './model';

const props = withDefaults(defineProps<{ inv: Invoice; paper?: Paper; digits?: 'bn' | 'en' }>(), { paper: 'A4', digits: 'bn' });
const d = computed(() => props.digits);
const m = (v: number) => moneyIn(v, d.value);
const n = (v: number | string) => digitsOf(v, d.value);
const thermal = computed(() => props.paper !== 'A4');
const voided = computed(() => props.inv.status === 'void');
/** ঢাকার সময়: ২৩/১০/২০২৬ ১৬:০৫ */
const when = computed(() => {
  const t = new Date(props.inv.issuedAt + 6 * 3600 * 1000);
  const p2 = (x: number) => String(x).padStart(2, '0');
  return n(`${p2(t.getUTCDate())}/${p2(t.getUTCMonth() + 1)}/${t.getUTCFullYear()} ${p2(t.getUTCHours())}:${p2(t.getUTCMinutes())}`);
});
const qtyOf = (q: number) => n(q.toLocaleString('en-IN', { maximumFractionDigits: 2 }));
const methods = computed(() => methodsOf(props.inv).map(methodText).join(', '));
// ক্রেতার ফোন ছাপা হয় না (কাউন্টারে পড়ে থাকা কাগজ বা ছবিতে ছড়ায়) — শুধু নাম
const who = computed(() => props.inv.customer?.name ?? '');
const demo = computed(() => isDemo(props.inv));
const pending = computed(() => isPending(props.inv));
const qrSize = computed(() => (props.paper === '58' ? 96 : props.paper === '80' ? 112 : 104));
</script>

<template>
  <article :class="['rcpt', `p${paper}`, voided && 'void']" :aria-label="T.receipt() + ' ' + n(inv.no)" data-testid="receipt">
    <div v-if="voided" class="rcpt-stamp" aria-hidden="true">{{ T.voidStamp() }}</div>
    <div v-if="demo" class="rcpt-demo" data-testid="receipt-demo">{{ T.demoMark() }}</div>
    <header class="rcpt-head">
      <div class="rcpt-brand"><span class="rcpt-logo"><Icon name="bloodtype" /></span>Lifolo</div>
      <div class="rcpt-biz">{{ inv.bizName }}</div>
      <div v-if="inv.bizAddress" class="rcpt-sub">{{ inv.bizAddress }}</div>
      <div v-if="inv.bizPhone" class="rcpt-sub">{{ n(inv.bizPhone) }}</div>
    </header>
    <div class="rcpt-meta">
      <div><span>{{ T.receiptNo() }}</span><b class="rcpt-no">{{ n(inv.no) }}</b></div>
      <div><span>{{ T.issued() }}</span><span>{{ when }}</span></div>
      <div><span>{{ kindText(inv.kind) }}</span><span v-if="who">{{ who }}</span></div>
    </div>

    <table v-if="!thermal" class="rcpt-table">
      <thead>
        <tr><th class="l">{{ T.item() }}</th><th>{{ T.qty() }}</th><th>{{ T.rate() }}</th><th>{{ tr('Discount', 'ছাড়') }}</th><th>{{ T.amount() }}</th></tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in inv.lines" :key="i">
          <td class="l">{{ l.name }}</td>
          <td>{{ qtyOf(l.qty) }}{{ l.unit ? ' ' + l.unit : '' }}</td>
          <td>{{ m(l.price) }}</td>
          <td>{{ l.disc ? m(l.disc) : '' }}</td>
          <td>{{ m(l.amount) }}</td>
        </tr>
      </tbody>
    </table>
    <ul v-else class="rcpt-lines">
      <li v-for="(l, i) in inv.lines" :key="i">
        <span class="rcpt-lname">{{ l.name }}</span>
        <span class="rcpt-lrow"><span>{{ qtyOf(l.qty) }} × {{ m(l.price) }}{{ l.disc ? ' − ' + m(l.disc) : '' }}</span><span>{{ m(l.amount) }}</span></span>
      </li>
    </ul>

    <dl class="rcpt-totals">
      <dt>{{ T.subtotal() }}</dt><dd>{{ m(inv.subtotal) }}</dd>
      <template v-if="inv.disc"><dt>{{ tr('Discount', 'ছাড়') }}</dt><dd>− {{ m(inv.disc) }}</dd></template>
      <template v-for="(f, i) in inv.fees" :key="'f' + i"><dt>{{ f.label }}</dt><dd>{{ m(f.amount) }}</dd></template>
      <dt class="big">{{ tr('Total', 'মোট') }}</dt><dd class="big">{{ m(inv.total) }}</dd>
      <dt>{{ T.paid() }}</dt><dd>{{ m(inv.paid) }}</dd>
      <template v-if="inv.due > 0"><dt class="due">{{ T.due() }}</dt><dd class="due">{{ m(inv.due) }}</dd></template>
      <template v-if="methods"><dt>{{ T.method() }}</dt><dd>{{ methods }}</dd></template>
    </dl>
    <p v-if="voided && inv.voidReason" class="rcpt-void-reason">{{ T.voidReason(inv.voidReason) }}</p>

    <footer class="rcpt-foot">
      <Qr v-if="inv.verifyCode" :text="inv.verifyUrl" :size="qrSize" />
      <span v-if="inv.verifyCode" class="rcpt-verify">{{ T.verifyLine(verifyShort(inv)) }}</span>
      <span v-if="pending || inv.offline" class="rcpt-pending" data-testid="receipt-offline">{{ T.offlineVerify() }}</span>
      <span class="rcpt-vat">{{ T.notVat() }}</span>
    </footer>
  </article>
</template>
