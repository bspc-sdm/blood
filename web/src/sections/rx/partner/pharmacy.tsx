// /work/pharmacy/orders/* — ফার্মেসির কাউন্টার (আগে /doctor প্যানেলে ছিল): প্রেসক্রিপশনের ওষুধ দেওয়া, অনলাইন অর্ডার, স্টক
// (অ্যাপের PharmacyRxSection, DispenseDialog, AcceptOrderDialog, OnlineSettingsDialog, MissedDialog, StockDialog)
import { matchPath, useLocation, useNavigate } from 'react-router-dom';
import { PillTabs } from '@/ui/kit';
import { useLang, tr } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { PartnerPage, usePartnerGate } from './common';
import { DispenseTab } from './dispense';
import { OnlineTab } from './online';
import { ItemsTab } from './books/items';
import { BuyTab } from './books/buy';
import { SalesTab } from './books/sales';
import { ReportTab } from './books/report';
import type { PharmOrder } from './model';

const BASE = '/work/pharmacy/orders';

export function PharmacyRxWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const nav = useNavigate();
  const gate = usePartnerGate('pharmacy');
  const title = tr('Prescriptions & orders', 'প্রেসক্রিপশন ও অর্ডার');
  const orders = useCall<{ items: PharmOrder[] }>('rx_orders_pharmacy', {}, { enabled: !gate, refetchInterval: 60_000 });
  if (gate) return <PartnerPage title={title} backTo="/work/pharmacy">{gate}</PartnerPage>;

  const m = (p: string) => matchPath({ path: BASE + p, end: true }, pathname);
  const rx = m('/rx/:code');
  // হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬): ‘স্টক’ এখন ‘পণ্য ও স্টক’ (দাম/পরিমাণ ঐচ্ছিক), সাথে মাল কেনা, বিক্রি, হিসাব
  const tab = m('/online') ? 'online' : m('/stock') ? 'stock' : m('/buy') ? 'buy' : m('/sales') ? 'sales' : m('/books') ? 'books' : 'dispense';
  const list = orders.data?.items ?? [];
  const shopNew = list.filter((o) => o.mode !== 'online' && o.status === 'sent').length;
  const onlineNew = list.filter((o) => o.mode === 'online' && o.status === 'sent').length;

  return (
    <PartnerPage title={title} backTo="/work/pharmacy">
      <div className="rxp-tabs">
        <PillTabs
          label={title}
          value={tab}
          onChange={(k) => nav(k === 'dispense' ? BASE : `${BASE}/${k}`)}
          tabs={[
            { key: 'dispense', label: tr('Give medicines', 'ওষুধ দিন'), icon: 'medication', count: shopNew },
            { key: 'online', label: tr('Online orders', 'অনলাইন অর্ডার'), icon: 'local_shipping', count: onlineNew },
            { key: 'stock', label: tr('Products & stock', 'পণ্য ও স্টক'), icon: 'list_alt' },
            { key: 'buy', label: tr('Stock in', 'মাল কেনা'), icon: 'storefront' },
            { key: 'sales', label: tr('Sales', 'বিক্রি'), icon: 'payments' },
            { key: 'books', label: tr('Accounts', 'হিসাব'), icon: 'history' },
          ]}
        />
      </div>
      {tab === 'online' ? <OnlineTab orders={orders} /> : tab === 'stock' ? <ItemsTab /> : tab === 'buy' ? <BuyTab /> : tab === 'sales' ? <SalesTab /> : tab === 'books' ? <ReportTab /> : <DispenseTab code={rx?.params.code ?? null} orders={orders} />}
    </PartnerPage>
  );
}
export default PharmacyRxWorkspace;
