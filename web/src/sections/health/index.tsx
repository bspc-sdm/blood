// স্বাস্থ্য: আলোচনা, টিপস, পেশাদার ব্যাজ, আমার প্রেসক্রিপশন — /health/* আর /prescriptions/*
// (অ্যাপের MainScreen-এর "স্বাস্থ্য" ট্যাব: আলোচনা / টিপস; প্রেসক্রিপশন আলাদা পাতা)
import { Navigate, matchPath, useLocation } from 'react-router-dom';
import { NotFound } from '@/pages/NotFound';
import { DiscussionPage, AskPage } from './Discussion';
import { TipsPage, PostTipPage } from './Tips';
import { BadgePage } from './Badge';
import { PrescriptionsPage } from './Prescriptions';
import './health.css';

export default function Section() {
  const { pathname } = useLocation();
  const p = pathname.replace(/\/+$/, '') || '/';
  const m = (pattern: string) => matchPath({ path: pattern, end: true }, p);

  if (p === '/health') return <Navigate to="/health/discussion" replace />;
  if (m('/health/discussion')) return <DiscussionPage id={null} />;
  const q = m('/health/question/:id');
  if (q) return <DiscussionPage id={q.params.id ?? null} />;
  if (m('/health/ask')) return <AskPage />;
  if (m('/health/tips')) return <TipsPage id={null} />;
  const t = m('/health/tip/:id');
  if (t) return <TipsPage id={t.params.id ?? null} />;
  if (m('/health/post-tip')) return <PostTipPage />;
  if (m('/health/badge')) return <BadgePage />;
  if (m('/prescriptions')) return <PrescriptionsPage id={null} />;
  const r = m('/prescriptions/:id');
  if (r) return <PrescriptionsPage id={r.params.id ?? null} />;
  return <NotFound />;
}
