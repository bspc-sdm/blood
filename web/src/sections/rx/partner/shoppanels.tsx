// ফার্মেসির "দোকান" ট্যাবের (/work/pharmacy/shop) কাউন্টারের অংশ — দোকানের QR ও কোড, অনলাইনে বিক্রি, চেয়েও পাননি (২.২৭.০)।
// আগে "প্রেসক্রিপশন ও অর্ডার"-এর পাশে ছিল; এখন অর্ডারের পাতায় শুধু অর্ডার। আলাদা ভাগে আসে (React.lazy)।
import { MissedPanel, ShopCodePanel } from './dispense';
import { OnlineSettingsPanel } from './online';
import './rxpartner.css';

export default function ShopPanels() {
  return (
    <div className="col gap-16">
      <ShopCodePanel />
      <OnlineSettingsPanel />
      <MissedPanel />
    </div>
  );
}
