// "ওষুধ খুঁজুন" (অ্যাপের MedFind.kt): তালিকা থেকে ওষুধ বেছে কাছের দোকানগুলোকে জিজ্ঞেস করা;
// যে দোকান "আছে" বলে, সেখানে সরাসরি ফোন। দাম, অর্ডার কিছুই অ্যাপে নেই।
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, EmptyState, ErrorText, IconButton, InfoText, PhoneLine, SkeletonCards } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { AreaPicker, useFilterArea } from '@/ui/AreaPicker';
import { ListDetail } from '@/shell/templates';
import { useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatKm, timeAgo } from '@/lib/format';
import { areaLabel, ensureAreas } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { recordCall } from './data';
import { ReportDialog, ServiceTabs } from './ui';

interface Drug { i: number; name: string; generic: string; form: string; company: string }
interface FindShop { id: string; name: string; phone: string; address: string; openNow: boolean; open24: boolean; km: number | null }
interface MyFind { id: string; drug: Drug; area: string; open: boolean; notified: number; answered: number; have: FindShop[]; createdAt: number }

const drugTitle = (d: Drug) => d.name + (d.form ? ' · ' + d.form : '');
const drugDetail = (d: Drug) => [d.generic, d.form, d.company].filter(Boolean).join(' · ');

export function MedFind() {
  const s = useSession();
  const two = useTwoPane();
  const [, bump] = useState(0);
  useEffect(() => {
    ensureAreas().then(() => bump((n) => n + 1), () => {});
  }, []);
  const [params] = useSearchParams();
  const [query, setQuery] = useState(() => (params.get('q') ?? '').slice(0, 40));
  const [dq, setDq] = useState(query.trim());
  const [chosen, setChosen] = useState<Drug | null>(null);
  const [area, setArea] = useFilterArea();
  const [showArea, setShowArea] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [reporting, setReporting] = useState<string | null>(null);

  // লেখা থামলে তবেই সার্ভারে খোঁজা
  useEffect(() => {
    const t = setTimeout(() => setDq(query.trim()), 350);
    return () => clearTimeout(t);
  }, [query]);
  const found = useCall<{ items: Drug[] }>('drug_search', { q: dq }, { enabled: dq.length >= 2, staleTime: 10 * 60_000 });
  const results = dq.length >= 2 ? found.data?.items ?? [] : [];
  const searching = query.trim().length >= 2 && (found.isFetching || dq !== query.trim());

  const mine = useCall<{ items: MyFind[] }>('med_finds_mine', {}, { enabled: !s.guest });

  function ask() {
    const c = chosen;
    if (!c) return;
    if (!area) {
      setErr(tr('Choose a district', 'জেলা বেছে নিন'));
      return;
    }
    const gps = s.place?.source === 'gps' ? s.place : null;
    s.requireLogin(tr('Log in to ask pharmacies', 'দোকানে জিজ্ঞেস করতে লগইন করুন'), async () => {
      setBusy(true);
      setErr(null);
      setInfo(null);
      try {
        const r = await call<{ ok?: boolean; noPharmacy?: boolean; noneOpen?: boolean; notified?: number }>('med_find', {
          i: c.i, district: area.district, upazilaId: area.upazilaId ?? null, lat: gps?.lat ?? null, lng: gps?.lng ?? null,
        });
        if (r.noPharmacy) setErr(tr('No pharmacy in this district has joined the app yet.', 'এই জেলায় এখনো অ্যাপে কোনো ফার্মেসি যোগ হয়নি।'));
        else if (r.noneOpen) setErr(tr('No pharmacy nearby can take a question right now (closed or busy). Please try again later.', 'এই মুহূর্তে কাছের কোনো ফার্মেসি খোলা বা জিজ্ঞাসা নেওয়ার মতো নেই। একটু পরে আবার চেষ্টা করুন।'));
        else {
          const n = r.notified ?? 0;
          setInfo(tr(`Asked ${n} nearby pharmacies. Answers will appear below, with a notification.`, `কাছের ${num(n)}টা দোকানকে জিজ্ঞেস করা হয়েছে। উত্তর নিচে আসবে, নোটিফিকেশনও পাবেন।`));
          setChosen(null);
          setQuery('');
          mine.refetch();
        }
      } catch (e) {
        setErr(errorText(e));
      } finally {
        setBusy(false);
      }
    });
  }

  async function close(id: string) {
    try {
      await call('med_find_close', { id });
      mine.refetch();
    } catch (e) {
      setErr(errorText(e));
    }
  }

  const searchBox = (
    <div className="col gap-8">
      <label className="services-search">
        <Icon name="search" />
        <span className="sr-only">{tr('Medicine name', 'ওষুধের নাম')}</span>
        <input
          className="input"
          value={query}
          maxLength={40}
          autoComplete="off"
          placeholder={tr('e.g. Napa Extend, Seclo, Esomeprazole', 'যেমন: Napa Extend, Seclo, Esomeprazole')}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>
      {results.length ? (
        <div className="col gap-6">
          {results.map((d) => (
            <button
              key={d.i}
              type="button"
              className="services-drug"
              onClick={() => {
                setChosen(d);
                setErr(null);
                setInfo(null);
              }}
            >
              <span className="t-title-s">{drugTitle(d)}</span>
              <span className="t-body-s muted">{[d.generic, d.company].filter(Boolean).join(' · ')}</span>
            </button>
          ))}
        </div>
      ) : null}
      {searching ? (
        <p className="t-body-s muted">{tr('Searching…', 'খোঁজা হচ্ছে…')}</p>
      ) : query.trim().length >= 2 && !results.length && found.isFetched ? (
        <p className="t-body-s muted">{tr('Not in the list — call a nearby pharmacy and ask.', 'তালিকায় নেই — কাছের ফার্মেসিতে ফোন করে জিজ্ঞেস করুন।')}</p>
      ) : null}
    </div>
  );

  const chosenCard = chosen ? (
    <div className="card col gap-12">
      <div className="row">
        <div className="col gap-2 grow">
          <span className="t-title-m">{chosen.name}</span>
          <span className="t-body-s muted">{drugDetail(chosen)}</span>
        </div>
        <Button variant="text" onClick={() => setChosen(null)}>{tr('Change', 'বদলান')}</Button>
      </div>
      <span className="t-title-s">{tr('Where', 'কোথায়')}</span>
      <button type="button" className="area-btn" onClick={() => setShowArea(true)}>
        <Icon name="location_on" />
        <span>{area ? areaLabel(area.district, area.upazilaId) : tr('Choose area', 'এলাকা বেছে নিন')}</span>
        <Icon name="expand_more_round" />
      </button>
      <p className="t-body-s muted">{tr("If it's a prescription medicine, the pharmacy will need to see the prescription.", 'প্রেসক্রিপশনের ওষুধ হলে কিনতে দোকানে প্রেসক্রিপশন দেখাতে হবে।')}</p>
      <ErrorText>{err}</ErrorText>
      <Button block icon="send" busy={busy} onClick={ask}>{tr('Ask nearby pharmacies', 'কাছের দোকানগুলোকে জিজ্ঞেস করুন')}</Button>
    </div>
  ) : null;

  const finds = mine.data?.items ?? [];
  const searches = s.guest ? null : mine.isLoading ? (
    <SkeletonCards n={2} h={110} />
  ) : finds.length ? (
    <div className="col gap-12">
      <div className="row">
        <span className="t-title-s grow">{tr('My searches', 'আমার খোঁজ')}</span>
        <Button variant="text" busy={mine.isFetching} onClick={() => mine.refetch()}>{tr('Refresh', 'রিফ্রেশ')}</Button>
      </div>
      {finds.map((f) => (
        <MyFindCard key={f.id} f={f} onReport={(id) => setReporting(id)} onClose={() => close(f.id)} guest={s.guest} />
      ))}
    </div>
  ) : null;

  const list = (
    <div className="list-inner">
      <ServiceTabs kind="pharmacy" sub="medicine" />
      {chosen ? chosenCard : searchBox}
      {!chosen ? <ErrorText>{err}</ErrorText> : null}
      <InfoText>{info}</InfoText>
      {!two ? searches : null}
    </div>
  );

  const placeholder = searches ?? (
    <EmptyState icon="medication" title={tr('My searches', 'আমার খোঁজ')}>
      {s.guest ? <Button variant="tonal" onClick={s.login}>{tr('Log in', 'লগইন')}</Button> : null}
    </EmptyState>
  );

  return (
    <>
      <ListDetail list={list} detail={null} placeholder={<div className="col gap-12">{placeholder}</div>} />
      {showArea ? (
        <AreaPicker
          value={area ? { district: area.district, upazilaId: null } : null}
          allowAllDistrict
          onClose={() => setShowArea(false)}
          onPicked={(v) => {
            setArea({ district: v.district, upazilaId: v.upazilaId ?? null, unionId: null });
            setErr(null);
            setShowArea(false);
          }}
        />
      ) : null}
      {reporting ? <ReportDialog kind="pharmacy" id={reporting} onClose={() => setReporting(null)} /> : null}
    </>
  );
}

function MyFindCard({ f, onReport, onClose, guest }: { f: MyFind; onReport: (shopId: string) => void; onClose: () => void; guest: boolean }) {
  return (
    <div className="card col gap-8">
      <span className="t-title-s ellipsis">{drugTitle(f.drug)}</span>
      <span className="t-body-s muted">
        {[f.area, timeAgo(f.createdAt), tr(`asked ${f.notified} shops`, `${num(f.notified)}টা দোকানে জিজ্ঞেস`), f.open ? tr('open', 'খোলা') : tr('closed', 'বন্ধ')].filter(Boolean).join(' · ')}
      </span>
      {!f.have.length ? (
        <span className="t-body-s muted">
          {f.open ? tr("No pharmacy has said yes yet. You'll get a notification.", 'এখনো কোনো দোকান "আছে" বলেনি। বললে নোটিফিকেশন পাবেন।') : tr('No pharmacy said it has this.', 'কোনো দোকান "আছে" বলেনি।')}
        </span>
      ) : null}
      {f.have.map((sh) => (
        <div key={sh.id} className="col gap-6 services-shop">
          <hr className="divider" />
          <div className="row top">
            <div className="col gap-2 grow">
              <span className="t-title-s">{sh.name}</span>
              <span className="t-body-s c-primary">
                {[tr('Has it', 'আছে বলেছে'), sh.km != null ? formatKm(sh.km) : null, sh.open24 ? tr('24 hours', '২৪ ঘণ্টা') : sh.openNow ? tr('open now', 'এখন খোলা') : tr('closed now', 'এখন বন্ধ')].filter(Boolean).join(' · ')}
              </span>
              {sh.address ? <span className="t-body-s muted">{sh.address}</span> : null}
            </div>
            <IconButton small icon="flag" label={tr('Report', 'রিপোর্ট')} onClick={() => onReport(sh.id)} />
          </div>
          <PhoneLine phone={sh.phone} onCall={() => recordCall('pharmacy', sh.id, guest)} />
        </div>
      ))}
      {f.open ? (
        <div>
          <Button variant="text" onClick={onClose}>{tr('Found it — stop asking', 'পেয়ে গেছি — খোঁজ বন্ধ করুন')}</Button>
        </div>
      ) : null}
    </div>
  );
}
