// হোম: আশেপাশের রক্তের রিকোয়েস্ট — অ্যাপের HomeTab (MainScreen.kt)।
// অতিথি: সার্ভার থেকে (guest_requests); লগইন করা: সার্ভার থেকে (blood_requests_near) geohash দিয়ে, দেখার গ্রুপ মিলিয়ে, নিজেরগুলো বাদ।
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button, EmptyState, ErrorBox, Fab, IconButton, ListRow, PhoneLine, SkeletonCards, toast } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { PlacePicker } from '@/ui/AreaPicker';
import { ListDetail } from '@/shell/templates';
import { usePageTitle } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { useSession, type Place } from '@/lib/session';
import { effectiveViewGroups, emptyProfile, isDonor } from '@/lib/profile';
import { DEFAULT_RADIUS_KM } from '@/lib/rules';
import { KEY, api, dropCommitments, nearbyOpen, processing, useInvalidate, type Nearby } from './data';
import { ProcessingCard, RequestCard } from './parts';
import { EmergencyBanner } from './Emergency';
import { MyRequestSection } from './MyRequests';
import { RequestDetail } from './RequestDetail';

export function Home({ selectedId }: { selectedId: string | null }) {
  const s = useSession();
  const nav = useNavigate();
  const invalidate = useInvalidate();
  usePageTitle(tr('Home', 'হোম'));
  const [picking, setPicking] = useState(false);
  const [placeBusy, setPlaceBusy] = useState(false);
  const [placeErr, setPlaceErr] = useState<string | null>(null);
  const [procBusy, setProcBusy] = useState<string | null>(null);

  const profile = s.profile ?? emptyProfile();
  const guest = s.guest;
  const place = s.place;
  const maxKm = guest ? DEFAULT_RADIUS_KM : profile.maxKm;
  const groups = guest ? null : effectiveViewGroups(profile);

  const nearby = useQuery<Nearby[]>({
    queryKey: [KEY, 'nearby', s.uid, place?.lat, place?.lng, groups?.join(','), maxKm],
    queryFn: () => (guest ? api.guestRequests(place!.lat, place!.lng, maxKm) : nearbyOpen(place!.lat, place!.lng, groups, s.uid, maxKm)),
    enabled: s.ready && !!place && (guest || s.profileLoaded),
    staleTime: 30_000,
  });

  // যেসব রিকোয়েস্টে রক্ত দিতে রাজি হয়েছেন (প্রসেসিং), কাউন্টডাউন সহ উপরে
  const commitments = guest ? [] : profile.commitments;
  const proc = useQuery({
    queryKey: [KEY, 'processing', s.uid, commitments.join(',')],
    queryFn: () => processing(s.uid!, commitments),
    enabled: !!s.uid && commitments.length > 0,
    staleTime: 30_000,
  });
  const processingList = commitments.length ? proc.data ?? [] : [];
  const procIds = new Set(processingList.map((r) => r.id));
  const others = (nearby.data ?? []).filter((x) => !procIds.has(x.r.id));

  // শেষ হয়ে যাওয়া রিকোয়েস্টে: রক্ত দিয়েছি / দিইনি
  async function answer(id: string, op: 'donated' | 'cancel') {
    setProcBusy(id);
    try {
      await api.updateResponse(id, op);
      if (s.uid) await dropCommitments(s.uid, [id]).catch(() => {});
      await s.reloadProfile();
    } catch (e) {
      toast(errorText(e));
    } finally {
      setProcBusy(null);
      invalidate();
    }
  }

  async function pickPlace(p: Place) {
    setPlaceBusy(true);
    setPlaceErr(null);
    try {
      await s.setPlace(p);
      setPicking(false);
    } catch (e) {
      setPlaceErr(errorText(e));
    } finally {
      setPlaceBusy(false);
    }
  }

  const newRequest = () => s.requireLogin(tr('Log in to request blood', 'রক্তের রিকোয়েস্ট দিতে লগইন করুন'), () => nav('/request/new'));
  const open = (id: string) => nav(`/request/${id}`);
  const areaName = place ? districtLabel(place.placeName) || tr('My location', 'আমার লোকেশন') : tr('Choose area', 'এলাকা বেছে নিন');

  const list = (
    <div className="list-inner">
      {!isDonor(profile) ? (
        <button
          type="button"
          className="blood-become"
          onClick={() => s.requireLogin(tr('Log in to become a blood donor', 'রক্তদাতা হতে লগইন করুন'), () => {}, { donor: true })}
        >
          <Icon name="volunteer_activism" size={22} />
          <span className="grow">{tr('I want to donate blood', 'রক্ত দিতে চাই')}</span>
          <Icon name="chevron_right" size={20} />
        </button>
      ) : null}

      {processingList.map((r) => (
        <ProcessingCard
          key={'p_' + r.id}
          r={r}
          selected={r.id === selectedId}
          onClick={() => open(r.id)}
          onDonated={() => answer(r.id, 'donated')}
          onNotDonated={() => answer(r.id, 'cancel')}
          busy={procBusy === r.id}
        />
      ))}

      <div className="row blood-head">
        <h2 className="t-title-l grow">{tr('Nearby Requests', 'আশেপাশের রিকোয়েস্ট')}</h2>
        <IconButton icon="refresh" label={tr('Refresh', 'রিফ্রেশ')} onClick={() => invalidate()} />
        <button type="button" className="chip lead blood-area" onClick={() => setPicking(true)} aria-label={tr('Change area', 'এলাকা বদলান') + ': ' + areaName}>
          <Icon name="location_on" />
          <span className="ellipsis">{areaName}</span>
        </button>
        {others.length ? (
          <Button icon="add" square className="blood-wide-only" onClick={newRequest}>{tr('Request', 'রিকোয়েস্ট')}</Button>
        ) : null}
      </div>

      <EmergencyBanner district={place?.placeName} />
      {!guest ? <MyRequestSection /> : null}

      {!place ? (
        <div className="blood-empty"><EmptyState icon="location_on" title={tr('Choose area', 'এলাকা বেছে নিন')}>
          <Button icon="location_on" onClick={() => setPicking(true)}>{tr('Choose area', 'এলাকা বেছে নিন')}</Button>
        </EmptyState></div>
      ) : nearby.isLoading && !others.length ? (
        <SkeletonCards n={3} />
      ) : nearby.error ? (
        <ErrorBox error={tr("Couldn't load requests", 'রিকোয়েস্ট লোড হয়নি')} onRetry={() => nearby.refetch()} />
      ) : !others.length ? (
        <div className="blood-empty"><EmptyState
          icon="volunteer_activism"
          title={tr('Good news!', 'সুখবর!')}
          text={tr(`No requests within ${num(maxKm)} km right now.`, `এই মুহূর্তে ${num(maxKm)} কিমির মধ্যে কোনো রিকোয়েস্ট নেই।`)}
        >
          <Button icon="add" onClick={newRequest} style={{ marginTop: 16 }}>{tr('Create Request', 'রিকোয়েস্ট দিন')}</Button>
        </EmptyState></div>
      ) : (
        others.map(({ r, km }) => <RequestCard key={r.id} r={r} km={km} selected={r.id === selectedId} onClick={() => open(r.id)} />)
      )}

      {others.length ? <div className="blood-fab"><Fab icon="add" onClick={newRequest}>{tr('Request', 'রিকোয়েস্ট')}</Fab></div> : null}
      {picking ? <PlacePicker busy={placeBusy} error={placeErr} onPicked={pickPlace} onClose={() => setPicking(false)} /> : null}
    </div>
  );

  return (
    <ListDetail
      list={list}
      detail={selectedId ? <RequestDetail key={selectedId} id={selectedId} /> : null}
      placeholder={<NeedNow />}
    />
  );
}

/** বড় পর্দায় ডান ভাগ (কিছু বাছা না থাকলে): এখনই দরকার হলে সেবার শর্টকাট আর ৯৯৯ */
function NeedNow() {
  const nav = useNavigate();
  const items = [
    { to: '/services/ambulance', icon: 'ambulance', label: tr('Ambulance', 'অ্যাম্বুলেন্স') },
    { to: '/services/bloodbank', icon: 'bloodtype', label: tr('Blood bank', 'ব্লাড ব্যাংক') },
    { to: '/services/pharmacy', icon: 'medication', label: tr('Pharmacy', 'ফার্মেসি') },
  ];
  return (
    <div className="col gap-16 blood-neednow">
      <h2 className="t-title-l">{tr('Need it now?', 'এখনই দরকার?')}</h2>
      <div className="col gap-4 blood-shortcuts">
        {items.map((it) => <ListRow key={it.to} icon={it.icon} title={it.label} onClick={() => nav(it.to)} />)}
      </div>
      <div className="card col blood-999">
        <span className="t-body-m">{tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা')}</span>
        <PhoneLine phone="999" />
      </div>
    </div>
  );
}
