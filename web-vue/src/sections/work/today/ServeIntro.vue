<script setup lang="ts">
// "সেবা দিন" — যাঁর এখনো কোনো ভূমিকা নেই (বা অতিথি): কারা যোগ দিতে পারেন, আর প্রতিটার যোগ করার পথ
import { Icon } from '@/ui/kit';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { businessIcon } from '../shared';
import { TT } from './text';

const s = useSession();
const nav = useNav();
const rows = [
  { key: 'doctor', icon: 'stethoscope', title: TT.serveDoctor, sub: TT.serveDoctorSub, to: '/health/badge' },
  { key: 'pharmacy', icon: businessIcon('pharmacy'), title: TT.pharmacy, sub: () => '', to: '/work/pharmacy/new' },
  { key: 'lab', icon: businessIcon('lab'), title: TT.lab, sub: () => '', to: '/work/lab/new' },
  { key: 'bloodbank', icon: businessIcon('bloodbank'), title: TT.bloodbank, sub: () => '', to: '/work/bloodbank/new' },
  { key: 'ambulance', icon: businessIcon('ambulance'), title: TT.ambulance, sub: () => '', to: '/work/ambulance/new' },
];
const go = (to: string) => s.requireLogin(TT.serveLogin(), () => nav(to));
</script>

<template>
  <div class="td-serve" data-testid="serve-intro">
    <div class="col gap-4">
      <h2 class="t-headline">{{ TT.serveTitle() }}</h2>
      <p class="t-body-m muted">{{ TT.serveText() }}</p>
    </div>
    <div class="work-choose">
      <button v-for="r in rows" :key="r.key" type="button" class="work-choice" :data-testid="`serve-${r.key}`" @click="go(r.to)">
        <span class="work-ic"><Icon :name="r.icon" /></span>
        <span class="col gap-2 grow">
          <span class="t-title-m">{{ r.title() }}</span>
          <span v-if="r.sub()" class="t-body-s muted">{{ r.sub() }}</span>
        </span>
        <Icon name="chevron_right" class="work-chev" />
      </button>
    </div>
  </div>
</template>
