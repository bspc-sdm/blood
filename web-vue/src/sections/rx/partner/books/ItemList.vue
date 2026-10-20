<script setup lang="ts">
import { computed } from 'vue';
import { Button, Tag, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useWide } from '../common';
import { discText, expiryText, money, qtyText, unitText, type ShopItem } from './model';
import StockSwitch from './StockSwitch.vue';

const props = defineProps<{ items: ShopItem[]; total: number; onChanged: () => void }>();
const emit = defineEmits<{ edit: [i: ShopItem]; adjust: [i: ShopItem] }>();
const wide = useWide();
const list = computed(() => props.items.slice(0, 400));
const soon = (i: ShopItem) => i.expDays != null && i.expDays <= 60;
const cardLine = (i: ShopItem) =>
  [i.qty != null ? `${qtyText(i.qty)} ${unitText(i.unit)}`.trim() : '', i.cost != null ? tr('cost ', 'কেনা ') + money(i.cost) : '', i.price != null ? tr('price ', 'বিক্রি ') + money(i.price) : ''].filter(Boolean).join(' · ');
</script>

<template>
  <p v-if="!total" class="t-body-m muted">{{ tr('No products yet', 'এখনো কোনো পণ্য নেই') }}</p>
  <p v-else-if="!items.length" class="t-body-m muted">{{ tr('No match', 'মেলেনি') }}</p>
  <template v-else>
    <div v-if="wide" class="table-wrap rxp-table-wrap">
      <table class="table rxp-table bk-table">
        <thead>
          <tr>
            <th>{{ tr('Product', 'পণ্য') }}</th>
            <th class="bk-r">{{ tr('Qty', 'পরিমাণ') }}</th>
            <th class="bk-r">{{ tr('Cost', 'কেনা দাম') }}</th>
            <th class="bk-r">{{ tr('Sale price', 'বিক্রি দাম') }}</th>
            <th>{{ tr('Expiry', 'মেয়াদ') }}</th>
            <th>{{ tr('In stock', 'স্টকে আছে') }}</th>
            <th><span class="sr-only">{{ tr('Edit', 'বদলান') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="i in list" :key="i.id" :class="cx(!i.inStock && 'bk-out')">
            <td>
              <div class="col gap-2">
                <span class="row gap-6"><span class="semi">{{ i.name }}</span><Tag v-if="!i.inStock" tone="error">{{ tr('Out of stock', 'স্টকে নেই') }}</Tag><Tag v-else-if="i.low" tone="info">{{ tr('Low', 'কম') }}</Tag></span>
                <span v-if="i.generic || i.form" class="t-body-s muted">{{ [i.generic, i.form].filter(Boolean).join(' · ') }}</span>
              </div>
            </td>
            <td class="bk-r num rxp-nowrap">{{ qtyText(i.qty) }}<span v-if="i.qty != null && i.unit" class="muted">{{ " " + unitText(i.unit) }}</span></td>
            <td class="bk-r num">{{ money(i.cost) }}</td>
            <td class="bk-r">
              <div class="col gap-2 bk-nowrap">
                <span class="num">{{ money(i.price) }}</span>
                <span v-if="i.disc" class="t-body-s c-primary num">−{{ discText(i.disc) }} = {{ money(i.salePrice) }}</span>
              </div>
            </td>
            <td><span v-if="i.expiry" :class="cx('t-body-s bk-nowrap', soon(i) ? 'c-error semi' : 'muted')">{{ expiryText(i.expiry) }}{{ i.expDays != null && i.expDays < 0 ? tr(' (expired)', ' (মেয়াদ শেষ)') : '' }}</span></td>
            <td><StockSwitch :i="i" :on-changed="onChanged" /></td>
            <td class="rxp-td-actions">
              <div class="row gap-4 rxp-row-actions">
                <Button small variant="text" icon="edit" @click="emit('edit', i)">{{ tr('Edit', 'বদলান') }}</Button>
                <Button small variant="text" @click="emit('adjust', i)">{{ tr('Adjust', 'নষ্ট/গণনা') }}</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="col gap-8">
      <div v-for="i in list" :key="i.id" :class="cx('bk-card', !i.inStock && 'bk-out')">
        <div class="row top">
          <div class="col gap-2 grow">
            <span class="row wrap gap-6"><span class="semi">{{ i.name }}</span><Tag v-if="!i.inStock" tone="error">{{ tr('Out of stock', 'স্টকে নেই') }}</Tag><Tag v-else-if="i.low" tone="info">{{ tr('Low', 'কম') }}</Tag></span>
            <span class="t-body-s muted num">{{ cardLine(i) }}</span>
            <span v-if="i.disc" class="t-body-s c-primary">{{ tr('Disc ', 'ছাড় ') }}{{ discText(i.disc) }} → {{ money(i.salePrice) }}</span>
            <span v-if="i.expiry" :class="cx('t-body-s bk-nowrap', soon(i) ? 'c-error semi' : 'muted')">{{ expiryText(i.expiry) }}{{ i.expDays != null && i.expDays < 0 ? tr(' (expired)', ' (মেয়াদ শেষ)') : '' }}</span>
          </div>
          <StockSwitch :i="i" :on-changed="onChanged" />
        </div>
        <div class="row gap-4">
          <Button small variant="text" icon="edit" @click="emit('edit', i)">{{ tr('Edit', 'বদলান') }}</Button>
          <Button small variant="text" @click="emit('adjust', i)">{{ tr('Adjust', 'নষ্ট/গণনা') }}</Button>
        </div>
      </div>
    </div>
    <p v-if="items.length > 400" class="t-body-s muted">{{ tr('Search to see the rest', 'বাকিগুলো দেখতে খুঁজুন') }}</p>
  </template>
</template>
