<script setup lang="ts">
// একটা ভূমিকার অংশ ("আজকের কাজ"): উপরে প্রতিটা ব্যবসার অবস্থা (খোলা / বিরতি / বন্ধ; ব্লাড ব্যাংকে স্টক কবে হালনাগাদ),
// তারপর আজকের ২–৪টা সংখ্যা — চাপলে সেই সারি বাছা অবস্থায় খোলে। নতুন বা অসম্পূর্ণ হলে সংখ্যার জায়গায় "শুরু করতে যা বাকি"।
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { Icon, Tag } from '@/ui/kit';
import { num } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { useSession } from '@/lib/session';
import { TT } from './text';
import StatusControl from './StatusControl.vue';
import { hasStatus, roleIcon, roleLabel, roleSetup, roleTiles, type Role, type TodayBiz } from './model';

const props = defineProps<{ role: Role; bizs: TodayBiz[] }>();
const emit = defineEmits<{ changed: [] }>();
const s = useSession();
const tiles = computed(() => roleTiles(props.bizs, s.uid));
const setup = computed(() => roleSetup(props.bizs));
const setupDone = computed(() => setup.value.filter((x) => x.done).length);
const one = computed(() => props.bizs.length === 1);
const sub = computed(() => (one.value ? props.bizs[0].name : ''));
const pharmacyLive = computed(() => props.role === 'pharmacy' && props.bizs.some((b) => !b.listing));
</script>

<template>
  <section class="td-block" :data-role="role" :data-testid="`role-${role}`">
    <header class="td-block-head">
      <span class="td-ic"><Icon :name="roleIcon(role)" /></span>
      <span class="col grow" :style="{ minWidth: 0 }">
        <h3 class="t-title-s">{{ roleLabel(role) }}</h3>
        <span v-if="sub" class="t-body-s muted ellipsis">{{ sub }}</span>
      </span>
      <RouterLink v-if="pharmacyLive" to="/work/pharmacy/shop/sales" class="btn tonal small has-icon" data-testid="sell-now"><Icon name="payments" />{{ TT.sell() }}</RouterLink>
    </header>

    <ul class="td-st-list">
      <li v-for="b in bizs" :key="b.id" class="td-st-row">
        <span v-if="!one" class="t-label-l td-st-name">{{ b.name }}</span>
        <Tag v-if="b.listing" tone="info">{{ TT.pending() }}</Tag>
        <span v-else-if="b.noToday" class="t-body-s muted">{{ TT.notToday() }}</span>
        <StatusControl v-else-if="hasStatus(b)" :biz="b" @changed="emit('changed')" />
        <div v-else-if="b.kind === 'bloodbank'" class="row wrap gap-8 td-stock" data-testid="stock-line">
          <span :class="['t-body-s', b.stockStale && 'td-warn-text']">
            {{ b.stockUpdatedAt ? TT.stockAt(timeAgo(b.stockUpdatedAt)) : TT.stockNever() }}
          </span>
          <Tag v-if="b.stockStale" tone="error">{{ TT.stockOld() }}</Tag>
          <RouterLink to="/work/bloodbank" class="btn outline small has-icon"><Icon name="bloodtype" />{{ TT.act('stock') }}</RouterLink>
        </div>
      </li>
    </ul>

    <div v-if="setup.length" class="td-setup" data-testid="setup-list">
      <div class="row">
        <span class="t-title-s grow">{{ TT.setupTitle() }}</span>
        <span class="t-body-s muted">{{ TT.setupDone(setupDone, setup.length) }}</span>
      </div>
      <ul class="col gap-4">
        <li v-for="x in setup" :key="x.key">
          <component :is="x.link && !x.done ? RouterLink : 'span'" :to="x.link || undefined" :class="['td-setup-row', x.done && 'done']">
            <Icon :name="x.done ? 'check_circle' : 'radio_button_unchecked'" />
            <span class="grow">{{ TT.setupKey(x.key) }}</span>
            <Icon v-if="x.link && !x.done" name="chevron_right" class="work-chev" />
          </component>
        </li>
      </ul>
    </div>
    <div v-else-if="tiles.length" :class="['td-tiles', `n${tiles.length}`]" :aria-label="TT.numbersOf(roleLabel(role))" role="group">
      <RouterLink v-for="t in tiles" :key="t.key" :to="t.link" class="td-tile" :data-testid="`tile-${t.key}`">
        <span class="v">{{ t.money ? TT.taka(t.value) : num(t.value) }}</span>
        <span class="l"><Icon :name="t.icon" />{{ t.label }}</span>
      </RouterLink>
    </div>
  </section>
</template>
