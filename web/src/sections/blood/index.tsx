// হোম / রক্ত: আশেপাশের রিকোয়েস্ট (/), রিকোয়েস্টের বিস্তারিত (/request/:id), নতুন রিকোয়েস্ট (/request/new),
// আমার রিকোয়েস্ট (/my-requests, /my-requests/:id, /my-requests/:id/edit)।
// কয়েকটা পথে বসানো হয়, তাই কোন পাতা তা পুরো পথ (pathname) দেখে ঠিক হয়।
import { matchPath, useLocation } from 'react-router-dom';
import { useLang } from '@/lib/i18n';
import { NotFound } from '@/pages/NotFound';
import { Home } from './Home';
import { MyRequestsPage } from './MyRequests';
import { EditRequestPage, NewRequestPage } from './RequestForm';
import './blood.css';

export default function BloodSection() {
  useLang();
  const { pathname } = useLocation();
  const m = (pattern: string) => matchPath({ path: pattern, end: true }, pathname);

  if (m('/')) return <Home selectedId={null} />;
  if (m('/request/new')) return <NewRequestPage />;
  const req = m('/request/:id');
  if (req) return <Home selectedId={req.params.id!} />;
  if (m('/my-requests')) return <MyRequestsPage selectedId={null} />;
  const edit = m('/my-requests/:id/edit');
  if (edit) return <EditRequestPage id={edit.params.id!} />;
  const mine = m('/my-requests/:id');
  if (mine) return <MyRequestsPage selectedId={mine.params.id!} />;
  return <NotFound />;
}
