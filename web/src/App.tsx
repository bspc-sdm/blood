// পথ (route) — প্রতিটা অংশ আলাদা ফাইলে, দরকার হলে তবেই আনা হয় (প্রথম পাতা দ্রুত খোলে)
import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { AppShell } from './shell/AppShell';
import { Loading } from './ui/kit';
import { NotFound } from './pages/NotFound';

const Blood = lazy(() => import('./sections/blood'));
const Services = lazy(() => import('./sections/services'));
const Appointment = lazy(() => import('./sections/appointment'));
const Health = lazy(() => import('./sections/health'));
const Work = lazy(() => import('./sections/work'));
const Account = lazy(() => import('./sections/account'));

export default function App() {
  return (
    <AppShell>
      <Suspense fallback={<Loading />}>
        <Routes>
          {/* হোম: রক্তের রিকোয়েস্ট */}
          <Route path="/" element={<Blood />} />
          <Route path="/request/*" element={<Blood />} />
          <Route path="/my-requests/*" element={<Blood />} />
          {/* সেবা: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি */}
          <Route path="/services/*" element={<Services />} />
          {/* অ্যাপয়েন্টমেন্ট: ডাক্তার, বাসায় সেবা, আমার অ্যাপয়েন্টমেন্ট */}
          <Route path="/appointment/*" element={<Appointment />} />
          <Route path="/my-appointments/*" element={<Appointment />} />
          {/* স্বাস্থ্য: আলোচনা, টিপস, প্রেসক্রিপশন */}
          <Route path="/health/*" element={<Health />} />
          <Route path="/prescriptions/*" element={<Health />} />
          {/* আমার কাজ (ব্যবসা/চেম্বার) */}
          <Route path="/work/*" element={<Work />} />
          {/* অ্যাকাউন্ট: প্রোফাইল, সেটিংস, নোটিফিকেশন, সাহায্য, কেয়ারগিভার */}
          <Route path="/profile/*" element={<Account />} />
          <Route path="/settings/*" element={<Account />} />
          <Route path="/inbox/*" element={<Account />} />
          <Route path="/help/*" element={<Account />} />
          <Route path="/care/*" element={<Account />} />
          <Route path="/login" element={<Account />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </AppShell>
  );
}
