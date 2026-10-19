// প্রেসক্রিপশন লেখার ফর্মের ওষুধের সারি
import { num } from '@/lib/i18n';

export interface MedRow { key: number; name: string; form: string; strength: string; dose: string; timing: string; days: string; note: string; picked: boolean }
let medKey = 1;
export const newMed = (): MedRow => ({ key: medKey++, name: '', form: '', strength: '', dose: '1+0+1', timing: 'after', days: num(5), note: '', picked: false });
export const DAY_MS = 86400000;
