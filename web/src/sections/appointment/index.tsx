// অ্যাপয়েন্টমেন্ট অংশ: /appointment/* আর /my-appointments/* (App.tsx দুই জায়গায় বসায়)।
// কোন পাতা — পুরো ঠিকানা দেখে (matchPath); তালিকা আর বিস্তারিত একই কম্পোনেন্ট, তাই ফিল্টার হারায় না।
import { matchPath, useLocation } from 'react-router-dom';
import { NotFound } from '@/pages/NotFound';
import { DoctorsPage } from './Doctors';
import { HomeCarePage } from './HomeCare';
import { MyAppointmentsPage } from './MyAppointments';
import './appointment.css';

export default function AppointmentSection() {
  const { pathname } = useLocation();
  const m = (p: string) => matchPath({ path: p, end: true }, pathname);

  const doctor = m('/appointment/doctor/:id');
  if (m('/appointment') || doctor) return <DoctorsPage detailId={doctor?.params.id ?? null} />;

  const home = m('/appointment/home-care/:id');
  if (m('/appointment/home-care') || home) return <HomeCarePage detailId={home?.params.id ?? null} />;

  const mine = m('/my-appointments/:id');
  if (m('/my-appointments') || mine) return <MyAppointmentsPage detailId={mine?.params.id ?? null} />;

  return <NotFound />;
}
