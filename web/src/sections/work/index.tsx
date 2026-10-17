// "আমার কাজ" (/work/*) — নিজের অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি আর পেশাদারের চেম্বার (অ্যাপের Work.kt)
import { lazy, Suspense } from 'react';
import { matchPath, useLocation } from 'react-router-dom';
import { Loading } from '@/ui/kit';
import { useLang } from '@/lib/i18n';
import { NotFound } from '@/pages/NotFound';
import { WorkOverview } from './overview';
import { AddBusiness } from './add';
import { AmbulanceForm, MyAmbulances } from './ambulance';
import { MyPharmacy, PharmacyForm } from './pharmacy';
import { BloodBankForm, MyBloodBanks } from './bloodbank';
import { LabForm, MyLab } from './lab';
import './work.css';

const Pro = lazy(() => import('../appointment/pro'));
// প্রেসক্রিপশন (ডাক্তার ও সহকারী), ফার্মেসির অর্ডার/স্টক, প্যাথলজির অর্ডার/রিপোর্ট — আগে /doctor প্যানেলে ছিল
const DoctorRx = lazy(() => import('../rx/doctor'));
const PharmacyRx = lazy(() => import('../rx/partner/pharmacy'));
const LabOrders = lazy(() => import('../rx/partner/lab'));

export default function WorkSection() {
  useLang();
  const { pathname } = useLocation();
  const m = (p: string) => matchPath({ path: p, end: true }, pathname);

  if (matchPath({ path: '/work/pro/*', end: false }, pathname)) {
    return (
      <Suspense fallback={<Loading />}>
        <Pro />
      </Suspense>
    );
  }
  const lazyPage = (el: JSX.Element) => <Suspense fallback={<Loading />}>{el}</Suspense>;
  if (matchPath({ path: '/work/rx/*', end: false }, pathname)) return lazyPage(<DoctorRx />);
  if (matchPath({ path: '/work/pharmacy/orders/*', end: false }, pathname)) return lazyPage(<PharmacyRx />);
  if (matchPath({ path: '/work/lab/orders/*', end: false }, pathname)) return lazyPage(<LabOrders />);
  if (m('/work')) return <WorkOverview />;
  if (m('/work/add')) return <AddBusiness />;

  if (m('/work/ambulance')) return <MyAmbulances />;
  if (m('/work/ambulance/new')) return <AmbulanceForm id={null} />;
  const amb = m('/work/ambulance/:id');
  if (amb) return <AmbulanceForm key={amb.params.id} id={amb.params.id ?? null} />;

  if (m('/work/pharmacy')) return <MyPharmacy />;
  if (m('/work/pharmacy/new') || m('/work/pharmacy/edit')) return <PharmacyForm />;

  if (m('/work/bloodbank')) return <MyBloodBanks />;
  if (m('/work/bloodbank/new')) return <BloodBankForm id={null} />;
  const bb = m('/work/bloodbank/:id');
  if (bb) return <BloodBankForm key={bb.params.id} id={bb.params.id ?? null} />;

  if (m('/work/lab')) return <MyLab />;
  if (m('/work/lab/new') || m('/work/lab/edit')) return <LabForm />;

  return <NotFound />;
}
