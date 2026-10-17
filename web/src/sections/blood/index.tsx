// অস্থায়ী — এই অংশ বানানো হচ্ছে
import { EmptyState } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';

export default function Section() {
  usePageTitle('blood');
  return <EmptyState icon="info" title="blood" />;
}
