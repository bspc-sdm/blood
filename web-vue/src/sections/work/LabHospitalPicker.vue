<script lang="ts">
export interface LabHospital { id: string; nameEn: string; nameBn: string; district: string; upazilaId: number | null; address: string }
</script>

<script setup lang="ts">
// স্বাস্থ্য অধিদপ্তরের তালিকা থেকে সেন্টার (জেলা ধরে, নাম দিয়ে খোঁজা)
import { computed, ref } from 'vue';
import { Dialog, ErrorText, ListRow, Loading } from '@/ui/kit';
import type { AreaValue } from '@/ui/areaFilter';
import { useCall } from '@/lib/query';
import { isBangla, tr } from '@/lib/i18n';
import { bnNorm } from '@/lib/geo';
import AreaField from './AreaField.vue';

const props = defineProps<{ district: string | null }>();
const emit = defineEmits<{ pick: [h: LabHospital]; close: [] }>();
const dist = ref<AreaValue | null>(props.district ? { district: props.district, upazilaId: null } : null);
const q = ref('');
const res = useCall<{ items: LabHospital[] }>('hospitals', () => ({ district: dist.value?.district }), () => ({ enabled: !!dist.value, staleTime: 10 * 60_000 }));
const list = computed(() => {
  const t = bnNorm(q.value.trim());
  const all = res.data?.items ?? [];
  return (t ? all.filter((h) => bnNorm(h.nameBn).includes(t) || h.nameEn.toLowerCase().includes(t)) : all).slice(0, 200);
});
</script>

<template>
  <Dialog :title="tr('Choose from the DGHS list', 'স্বাস্থ্য অধিদপ্তরের তালিকা থেকে বেছে নিন')" @close="emit('close')">
    <AreaField :label="tr('District', 'জেলা')" :value="dist" @change="(v) => (dist = { district: v.district, upazilaId: null })" />
    <input v-model="q" class="input" :placeholder="tr('Search by name', 'নাম দিয়ে খুঁজুন')" :aria-label="tr('Search by name', 'নাম দিয়ে খুঁজুন')" />
    <div class="col gap-2 work-pick-list">
      <template v-if="dist">
        <Loading v-if="res.isLoading" />
        <ErrorText v-else-if="res.error" :text="res.error.message" />
        <p v-else-if="!list.length" class="t-body-m muted" :style="{ padding: '16px' }">{{ tr('No match', 'মেলেনি') }}</p>
        <template v-else>
          <ListRow
            v-for="h in list"
            :key="h.id"
            icon="local_hospital"
            :title="isBangla() && h.nameBn ? h.nameBn : h.nameEn"
            :sub="[isBangla() ? h.nameEn : h.nameBn, h.address].filter(Boolean).join(' · ')"
            :on-click="() => emit('pick', h)"
          >
            <template #trailing><span /></template>
          </ListRow>
        </template>
      </template>
    </div>
  </Dialog>
</template>
