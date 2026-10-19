// /work/pharmacy/orders/* — ফার্মেসির কাউন্টার (আগে /doctor প্যানেলে ছিল)
// ২.২৭.০ থেকে দুই ট্যাব (work/shared.tsx-এর PharmacyTabs; অ্যাপের MyWorkTabs.kt):
//  "অর্ডার" — /work/pharmacy/orders (আর /rx/:code, পুরোনো /online): কোড দিয়ে খোলা, নতুন · চলছে/তৈরি · শেষ (orders.tsx)
//  "দোকান" — /work/pharmacy/shop (তথ্য, QR, অনলাইনে বিক্রি, চেয়েও পাননি) আর ওয়েবের হিসাব-খাতা: /stock, /buy, /sales, /books
// (অ্যাপের PharmacyOrdersTab, DispenseDialog, AcceptOrderDialog, OnlineSettingsDialog, MissedDialog, StockDialog)
import { matchPath, useLocation } from 'react-router-dom';
import { useLang } from '@/lib/i18n';
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

export function PharmacyRxWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const gate = usePartnerGate('pharmacy');
  const title = myBusinessTitle('pharmacy');
  const orders = useCall<PharmOrders>('rx_orders_pharmacy', {}, { enabled: !gate, refetchInterval: 60_000 });
  if (gate) return <PartnerPage title={title} backTo="/work">{gate}</PartnerPage>;

  const m = (p: string) => matchPath({ path: BASE + p, end: true }, pathname);
  const rx = m('/rx/:code');
  // হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬): ‘পণ্য ও স্টক’, মাল কেনা, বিক্রি, হিসাব — এখন "দোকান" ট্যাবের ভেতরে
  const tab = m('/stock') ? 'stock' : m('/buy') ? 'buy' : m('/sales') ? 'sales' : m('/books') ? 'books' : 'orders';

  return (
    <PartnerPage title={title} backTo="/work">
      <PharmacyTabs active={tab} />
      {tab === 'stock' ? <ItemsTab /> : tab === 'buy' ? <BuyTab /> : tab === 'sales' ? <SalesTab /> : tab === 'books' ? <ReportTab /> : <OrdersTab code={rx?.params.code ?? null} orders={orders} />}
    </PartnerPage>
  );
}
export default PharmacyRxWorkspace;
