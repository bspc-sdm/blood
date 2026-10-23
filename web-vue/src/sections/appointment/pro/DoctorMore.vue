<script setup lang="ts">
// ডাক্তারের পাতার মাথায় "আরও": নতুন প্রেসক্রিপশন, রিপোর্ট, সহকারী, সেটআপ, বাসায় সেবা (doctorNav.ts)
import { RowMenu } from '@/ui/kit';
import { useDoctorNav } from './doctorNav';

const props = defineProps<{ type: string | null | undefined }>();
const n = useDoctorNav(() => props.type);
</script>

<template>
  <RowMenu :items="n.more.value" testid="doctor-more" />
</template>
