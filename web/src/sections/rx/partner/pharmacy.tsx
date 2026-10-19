// ফার্মেসির কাউন্টার আর হিসাব-খাতা (আগে /doctor প্যানেলে ছিল) — ২.৩০.০ থেকে তিন ট্যাব (work/shared.tsx-এর PharmacyTabs; অ্যাপের Pharmacy.kt):
//  "অর্ডার" — /work/pharmacy/orders (আর /rx/:code, পুরোনো /online): কোড দিয়ে খোলা, নতুন · চলছে/তৈরি · শেষ (orders.tsx)
//  "ওষুধ খোঁজা" — /work/pharmacy/finds (work/pharmacy.tsx)
//  "দোকান" — তথ্য /work/pharmacy/shop (work/pharmacy.tsx) · বিক্রি /shop/sales · স্টক /shop/stock (পণ্য ও স্টক, ?v=buy মাল কেনা) ·
//            এনালাইটিক্স /shop/analytics (লাভ-ক্ষতি) — শেষ তিনটা এখানে।
// পুরোনো লিংক: /work/pharmacy/orders/stock|buy|sales|books → নতুন জায়গায় (replace)।
import { Navigate, matchPath, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { PillTabs } from '@/ui/kit';
import { tr, useLang } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { PartnerPage, usePartnerGate } from './common';
import { OrdersTab } from './orders';
import { ItemsTab } from './books/items';
import { BuyTab } from './books/buy';
import { SalesTab } from './books/sales';
import { ReportTab } from './books/report';
import { PharmacyTabs, myBusinessTitle } from '../../work/shared';
import type { PharmOrders } from './model';

const BASE = '/work/pharmacy/orders';
const SHOP = '/work/pharmacy/shop';
/** পুরোনো হিসাব-খাতার ঠিকানা → নতুন */
const OLD: Record<string, string> = { stock: `${SHOP}/stock`, buy: `${SHOP}/stock?v=buy`, sales: `${SHOP}/sales`, books: `${SHOP}/analytics` };

export function PharmacyRxWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const gate = usePartnerGate('pharmacy');
  const title = myBusinessTitle('pharmacy');
  const old = matchPath({ path: `${BASE}/:k`, end: true }, pathname)?.params.k;
  const sub = matchPath({ path: `${SHOP}/:k`, end: true }, pathname)?.params.k;
  const tab = sub === 'sales' || sub === 'stock' || sub === 'analytics' ? sub : 'orders';
  const orders = useCall<PharmOrders>('rx_orders_pharmacy', {}, { enabled: !gate && tab === 'orders', refetchInterval: 60_000 });
  if (old && OLD[old]) return <Navigate to={OLD[old]} replace />;
  if (gate) return <PartnerPage title={title} backTo="/work">{gate}</PartnerPage>;

  const rx = matchPath({ path: `${BASE}/rx/:code`, end: true }, pathname);
  const buy = sp.get('v') === 'buy';

  return (
    <PartnerPage title={title} backTo="/work">
      <PharmacyTabs active={tab} />
      {tab === 'stock' ? (
        <>
          {/* স্টক: পণ্য ও স্টকের তালিকা আর মাল কেনা */}
          <div className="rxp-sub-pills">
            <PillTabs
              secondary
              label={tr('Stock', 'স্টক')}
              value={buy ? 'buy' : 'items'}
              onChange={(k) => nav(k === 'buy' ? `${SHOP}/stock?v=buy` : `${SHOP}/stock`, { replace: true })}
              tabs={[
                { key: 'items', label: tr('Products & stock', 'পণ্য ও স্টক'), icon: 'list_alt' },
                { key: 'buy', label: tr('Stock in', 'মাল কেনা'), icon: 'storefront' },
              ]}
            />
          </div>
          {buy ? <BuyTab /> : <ItemsTab />}
        </>
      ) : tab === 'sales' ? <SalesTab /> : tab === 'analytics' ? <ReportTab /> : <OrdersTab code={rx?.params.code ?? null} orders={orders} />}
    </PartnerPage>
  );
}
export default PharmacyRxWorkspace;
