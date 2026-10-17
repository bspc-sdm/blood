import { Link } from 'react-router-dom';
import { EmptyState } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { usePageTitle } from '@/shell/chrome';

export function NotFound() {
  usePageTitle(tr('Not found', 'পাওয়া যায়নি'));
  return (
    <EmptyState icon="info" title={tr('Page not found', 'পাতাটা পাওয়া যায়নি')}>
      <Link to="/" className="btn">{tr('Go home', 'হোমে যান')}</Link>
    </EmptyState>
  );
}
