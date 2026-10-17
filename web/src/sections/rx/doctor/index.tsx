// প্রেসক্রিপশন — ডাক্তার ও সহকারীর কাজের জায়গা (/work/rx/*), আগের /doctor প্যানেলের বদলে।
// অ্যাপের Prescriptions.kt (RxDoctorTab, DoctorPatientsTab, RxAssistScreen…) আর RxNet.kt (RxReviewsPane) — কম্পিউটারে চওড়া করে।
//   /work/rx               রোগী (আজকের অ্যাপয়েন্টমেন্ট + এন্ট্রি করা রোগী), ?patient= / ?appointment= বাছলে পাশে আগের প্রেসক্রিপশন
//   /work/rx/write         নতুন প্রেসক্রিপশন (?patient= / ?appointment=; না থাকলে রোগী বাছাই)
//   /work/rx/list          আমার লেখা প্রেসক্রিপশন
//   /work/rx/view/:id      একটা প্রেসক্রিপশন: কাগজ, ছাপা, বাতিল
//   /work/rx/reviews       রিপোর্ট দেখার অনুরোধ (সহকারী: টাকা মেলানো)
//   /work/rx/team          সহকারী (শুধু ডাক্তার)
// সহকারী: ?doctor=<uid> — কোন ডাক্তারের হয়ে; প্রতিটা কাজে doctorUid হিসেবে যায়।
import { Link, matchPath, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Button, EmptyState, ErrorText, PillTabs, Select, SkeletonCards } from '@/ui/kit';
import { Workspace } from '@/shell/templates';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { NotFound } from '@/pages/NotFound';
import { type Actor, type RxMe } from './model';
import { PatientsPage } from './Patients';
import { WritePage } from './Write';
import { RxListPage, RxViewPage } from './RxView';
import { ReviewsPage } from './Reviews';
import { TeamPage } from './Team';
import '../../health/health.css';
import './rxdoctor.css';

const BASE = '/work/rx';

export function DoctorRxWorkspace() {
  useLang();
  const { pathname } = useLocation();
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const s = useSession();
  const title = tr('Prescriptions', 'প্রেসক্রিপশন');
  const isDoctor = s.work.professional === 'doctor';
  const assisting = s.work.assisting;
  const me = useCall<RxMe>('rx_me', {}, { enabled: s.ready && !s.guest && isDoctor, staleTime: 5 * 60_000 });

  if (!s.ready || (!s.guest && !s.workLoaded && !s.workError)) {
    return (
      <Workspace title={title}>
        <SkeletonCards n={3} h={88} />
      </Workspace>
    );
  }
  if (s.guest) {
    return (
      <Workspace title={title}>
        <EmptyState icon="medication" title={title} text={tr('For verified doctors and their assistants', 'যাচাই করা ডাক্তার আর তাঁদের সহকারীর জন্য')}>
          <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
        </EmptyState>
      </Workspace>
    );
  }

  // কার হয়ে: ?doctor= (সহকারী), নইলে নিজে (ডাক্তার), নইলে প্রথম ডাক্তার
  const asked = sp.get('doctor');
  const pick = asked && asked !== s.uid ? assisting.find((d) => d.doctorUid === asked) : null;
  const actor: Actor | null = pick
    ? { role: 'assistant', doctorUid: pick.doctorUid, doctorName: pick.name }
    : isDoctor
      ? { role: 'doctor', doctorUid: null, doctorName: me.data?.name ?? s.profile?.name ?? '' }
      : assisting.length
        ? { role: 'assistant', doctorUid: assisting[0].doctorUid, doctorName: assisting[0].name }
        : null;

  if (!actor) {
    return (
      <Workspace title={title}>
        {s.workError ? <ErrorText>{s.workError}</ErrorText> : null}
        <EmptyState
          icon="medication"
          title={tr('Only verified doctors and their assistants can use this', 'শুধু যাচাই করা ডাক্তার আর তাঁদের সহকারী এটা ব্যবহার করতে পারেন')}
          text={tr("Are you a doctor's assistant? Ask the doctor to add your email as an assistant.", 'ডাক্তারের সহকারী? ডাক্তারকে বলুন আপনার ইমেইল সহকারী হিসেবে যোগ করতে।')}
        >
          <Link className="btn tonal" to="/work">{tr('My work', 'আমার কাজ')}</Link>
        </EmptyState>
      </Workspace>
    );
  }

  const doctor = actor.role === 'doctor';
  /** ?doctor= রেখে অন্য পাতায় */
  const link = (path: string, extra: Record<string, string> = {}) => {
    const q = new URLSearchParams(extra);
    if (actor.role === 'assistant' && actor.doctorUid) q.set('doctor', actor.doctorUid);
    const qs = q.toString();
    return BASE + path + (qs ? '?' + qs : '');
  };

  const sub = pathname.startsWith(BASE) ? pathname.slice(BASE.length).replace(/\/$/, '') : '';
  const m = (p: string) => matchPath({ path: p, end: true }, sub || '/');
  const tabs = doctor
    ? [
      { key: '', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
      { key: '/write', label: tr('New prescription', 'নতুন প্রেসক্রিপশন'), icon: 'edit_note' },
      { key: '/list', label: tr('Prescriptions', 'প্রেসক্রিপশন'), icon: 'medication' },
      { key: '/reviews', label: tr('Reports', 'রিপোর্ট'), icon: 'list_alt' },
      { key: '/team', label: tr('Assistants', 'সহকারী'), icon: 'group_round' },
    ]
    : [
      { key: '', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
      { key: '/reviews', label: tr('Payments', 'টাকা মেলানো'), icon: 'payments' },
    ];
  const cur = m('/view/:id') ? (doctor ? '/list' : '') : tabs.some((t) => t.key === sub) ? sub : '';

  let page: JSX.Element;
  const view = m('/view/:id');
  if (!sub || m('/')) page = <PatientsPage actor={actor} link={link} />;
  else if (m('/write')) page = doctor ? <WritePage actor={actor} chambers={me.data?.chambers ?? []} degrees={me.data?.degrees ?? ''} link={link} /> : <OnlyDoctor link={link} />;
  else if (m('/list')) page = doctor ? <RxListPage link={link} /> : <OnlyDoctor link={link} />;
  else if (view) page = doctor ? <RxViewPage id={view.params.id ?? ''} link={link} /> : <OnlyDoctor link={link} />;
  else if (m('/reviews')) page = <ReviewsPage actor={actor} link={link} />;
  else if (m('/team')) page = doctor ? <TeamPage /> : <OnlyDoctor link={link} />;
  else return <NotFound />;

  // কার হয়ে কাজ: নিজে (ডাক্তার হলে) আর যাঁদের সহকারী
  const options = [
    ...(isDoctor ? [{ value: '', label: tr('Myself (doctor)', 'নিজে (ডাক্তার)') }] : []),
    ...assisting.map((d) => ({ value: d.doctorUid, label: tr(`Assistant · ${d.name}`, `সহকারী · ${d.name}`) })),
  ];
  const selected = actor.role === 'doctor' ? '' : actor.doctorUid ?? '';
  const whoLine = doctor
    ? [actor.doctorName, me.data?.degrees].filter(Boolean).join(' · ')
    : tr(`Assistant of ${actor.doctorName}`, `${actor.doctorName}-এর সহকারী`);

  return (
    <Workspace
      title={title}
      intro={
        <div className="col gap-2">
          <h1 className="t-headline rxd-h1">{title}</h1>
          <span className="t-body-m muted">{whoLine}</span>
        </div>
      }
      actions={
        options.length > 1 ? (
          <div className="rxd-who">
            <Select
              aria-label={tr('Working for', 'কার হয়ে কাজ')}
              value={selected}
              options={options}
              onChange={(e) => {
                const v = e.target.value;
                // নিজে আর সহকারীর কাজ আলাদা — রোগীর তালিকায় ফেরা
                nav(BASE + (v ? `?doctor=${encodeURIComponent(v)}` : ''));
              }}
            />
          </div>
        ) : undefined
      }
    >
      <div className="rxd-tabs">
        <PillTabs tabs={tabs} value={cur} onChange={(k) => nav(link(k))} label={title} />
      </div>
      {page}
    </Workspace>
  );
}
export default DoctorRxWorkspace;

/** সহকারী এই পাতা খুলতে পারেন না (সার্ভারও দেয় না) */
function OnlyDoctor({ link }: { link: (p: string) => string }) {
  return (
    <EmptyState icon="stethoscope" title={tr('Only the doctor can do this', 'শুধু ডাক্তার নিজে এটা করতে পারেন')}>
      <Link className="btn tonal" to={link('')}>{tr('Patients', 'রোগী')}</Link>
    </EmptyState>
  );
}
