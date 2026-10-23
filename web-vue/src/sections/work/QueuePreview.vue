<script setup lang="ts">
// কাজের সারির পাতায় (অ্যাম্বুলেন্স, ফার্মেসির অর্ডার, প্যাথলজির অনুরোধ, ডাক্তারের আজকের রোগী) "রোগীরা আপনাকে যেভাবে দেখেন" —
// বড় কার্ড নয় (সেটা "আমার কাজ"-এর প্রথম পাতায় এক লাইনে আছে), এক লাইন: ছোট ছবি, নাম আর "দেখুন" (চাপলে পুরো কার্ড একটা জানালায়)।
// নিচে (slot) এখনকার অবস্থা (শুধু দেখায়, "বদলান" → /work) আর এই ব্যবসার নিজের সুইচ (ডেলিভারি, বাসা থেকে নমুনা)।
// খোলা/বন্ধ, খালি/ট্রিপে, আজ সিরিয়াল চলছে/বন্ধ বদলানো শুধু এক জায়গায় — /work-এর অবস্থার বোতাম (আমার কাজ CONTRACT §১, §২)।
import { ref } from 'vue';
import { Avatar, Button, Dialog, SkeletonCards } from '@/ui/kit';
import { useNav } from '@/lib/nav';
import ListingCard from '@/ui/listing/ListingCard.vue';
import { LT, type Listing } from '@/ui/listing/model';
import { TT } from './today/text';

defineProps<{ l: Listing | null; loading?: boolean; publicTo?: string | null }>();
const nav = useNav();
const open = ref(false);
</script>

<template>
  <section class="pv-box pv-row" data-testid="provider-preview">
    <SkeletonCards v-if="loading && !l" :n="1" :h="48" />
    <div v-else-if="l" class="td-pv-row">
      <Avatar :photo="l.photo || null" :icon="l.icon" :tint="l.tint" :size="36" />
      <span class="col grow" :style="{ minWidth: 0 }">
        <span class="semi ellipsis">{{ l.name }}</span>
        <span class="t-body-s muted ellipsis">{{ LT.previewTitle() }}</span>
      </span>
      <Button variant="outline" small data-testid="preview-see" @click="open = true">{{ TT.see() }}</Button>
    </div>
    <div v-if="$slots.default" class="pv-switches" data-testid="quick-switches"><slot /></div>
    <Dialog v-if="open && l" :title="LT.previewTitle()" @close="open = false">
      <ListingCard :l="l" preview />
      <template v-if="publicTo" #actions>
        <Button variant="tonal" @click="nav(publicTo)">{{ TT.openPublic() }}</Button>
      </template>
    </Dialog>
  </section>
</template>
