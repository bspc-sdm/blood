// "সেবা" (অ্যাপের ServicesTab): অ্যাম্বুলেন্স / ফার্মেসি (+ ওষুধ খুঁজুন) / ব্লাড ব্যাংক / প্যাথলজি।
// পথ: /services → /services/ambulance; /services/:kind; /services/:kind/:id; /services/pharmacy/medicine
// নিজের ব্যবসা এখানে নয় — "আমার কাজ" (/work)।
import { Navigate, matchPath, useLocation } from 'react-router-dom';
import { usePageTitle } from '@/shell/chrome';
import { tr, useLang } from '@/lib/i18n';
import { isKind } from './data';
import { Directory } from './Directory';
import { MedFind } from './MedFind';
import { RequestPage } from './AmbRequest';
import './services.css';

export default function Services() {
  useLang();
  usePageTitle(tr('Services', 'সেবা'));
  const { pathname } = useLocation();

  if (matchPath('/services/pharmacy/medicine', pathname)) return <MedFind />;
  // অ্যাম্বুলেন্স ডাকার অপেক্ষার পাতা (২.২৮.০)
  const req = matchPath('/services/ambulance/call/:id', pathname);
  if (req?.params.id) return <RequestPage key={req.params.id} id={req.params.id} />;
  const detail = matchPath('/services/:kind/:id', pathname);
  const kind = (detail ?? matchPath('/services/:kind', pathname))?.params.kind;
  if (!isKind(kind)) return <Navigate to="/services/ambulance" replace />;
  return <Directory key={kind} kind={kind} id={detail?.params.id ?? null} />;
}
