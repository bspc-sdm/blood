import { isBangla } from '@/lib/i18n';
import { upazila } from '@/lib/geo';

export const upLabels = (ids: number[]) => ids.map((id) => upazila(id)).filter(Boolean).map((u) => (isBangla() ? u!.bn : u!.en));
