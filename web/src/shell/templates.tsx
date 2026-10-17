// তিন রকম পাতা:
// ১. ListDetail — তালিকা + বিস্তারিত (ডেস্কটপ/বড় ট্যাবলেটে পাশাপাশি; মোবাইলে বিস্তারিত আলাদা পুরো পাতা)
// ২. Workspace — চওড়া কাজের পাতা ("আমার কাজ": টেবিল, পরিসংখ্যান)
// ৩. FormPage — ফর্ম, মাঝখানে এক কলাম
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { IconButton, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useDetailMode, usePageTitle, useSubPage, useTwoPane } from './chrome';

export function ListDetail({
  list, detail, placeholder, flush,
}: {
  /** তালিকার অংশ */
  list: ReactNode;
  /** বাছা জিনিসের বিস্তারিত (DetailPane দিয়ে মোড়ানো); কিছু বাছা না থাকলে null */
  detail: ReactNode | null;
  /** বড় পর্দায় কিছু বাছা না থাকলে ডান ভাগে যা থাকে */
  placeholder?: ReactNode;
  flush?: boolean;
}) {
  const open = detail != null;
  return (
    <div className={cx('ld', open ? 'two' : 'one')}>
      <section className={cx('ld-list', flush && 'flush')}>{list}</section>
      <aside className="ld-detail" aria-label={tr('Details', 'বিস্তারিত')}>
        {open ? detail : placeholder ? <div className="ld-placeholder ld-detail-inner">{placeholder}</div> : null}
      </aside>
    </div>
  );
}

/** বিস্তারিত ভাগ: মোবাইলে উপরে "পেছনে" বার (অ্যাপের BackTopBar), বড় পর্দায় শুধু শিরোনাম */
export function DetailPane({ title, onBack, backTo, actions, children, hideTitleWide }: { title: string; onBack?: () => void; backTo?: string; actions?: ReactNode; children: ReactNode; hideTitleWide?: boolean }) {
  const nav = useNavigate();
  const two = useTwoPane();
  useDetailMode(!two);
  const back = onBack ?? (() => (backTo ? nav(backTo) : nav(-1)));
  return (
    <>
      <div className="back-bar">
        <IconButton icon="arrow_back" label={tr('Back', 'পেছনে')} onClick={back} />
        <h1>{title}</h1>
        {actions}
      </div>
      <div className="ld-detail-inner">
        {two && !hideTitleWide ? (
          <div className="row">
            <h2 className="t-title-l grow">{title}</h2>
            {actions}
            <IconButton icon="close" label={tr('Close', 'বন্ধ')} onClick={back} />
          </div>
        ) : null}
        {children}
      </div>
    </>
  );
}

/** পুরো পাতা যার নিজের "পেছনে" বার লাগে (মোবাইলে উপরের বার লুকিয়ে) — যেমন ফর্ম */
export function SubPageBar({ title, onBack, backTo, actions }: { title: string; onBack?: () => void; backTo?: string; actions?: ReactNode }) {
  const nav = useNavigate();
  useDetailMode(true);
  useSubPage();
  return (
    <div className="back-bar sub-bar">
      <IconButton icon="arrow_back" label={tr('Back', 'পেছনে')} onClick={onBack ?? (() => (backTo ? nav(backTo) : nav(-1)))} />
      <h1>{title}</h1>
      {actions}
    </div>
  );
}

export function Workspace({ title, actions, children, intro }: { title: string; actions?: ReactNode; children: ReactNode; intro?: ReactNode }) {
  usePageTitle(title);
  return (
    <div className="ws">
      <div className="ws-inner">
        {actions || intro ? (
          <div className="row wrap" style={{ justifyContent: 'space-between' }}>
            <div className="grow">{intro}</div>
            {actions ? <div className="row wrap">{actions}</div> : null}
          </div>
        ) : null}
        {children}
      </div>
    </div>
  );
}

/** ফর্ম পাতা: মোবাইলে পুরো পাতা "পেছনে" বারসহ; বড় পর্দায় মাঝখানে বাক্স */
export function FormPage({ title, onBack, backTo, children, boxed = true }: { title: string; onBack?: () => void; backTo?: string; children: ReactNode; boxed?: boolean }) {
  usePageTitle(title);
  return (
    <>
      <SubPageBar title={title} onBack={onBack} backTo={backTo} />
      <div className="form-page">
        <div className={cx('form-inner', boxed && 'boxed')}>{children}</div>
      </div>
    </>
  );
}
