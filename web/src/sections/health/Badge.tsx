// পেশাদার ব্যাজ: আবেদন বা অনুমোদিত ব্যাজের তথ্য সংশোধন, কাগজের ছবিসহ — অ্যাপের BadgeApplyDialog, Verify.kt, Profile.kt-এর ব্যাজ সারি
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, EmptyState, ErrorText, IconLine, InfoText, Loading, Tag, TextInput } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { FormPage } from '@/shell/templates';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { BADGES, badgeIcon, badgeLabel, goBack, imgSrc, toDocumentBase64, useMe } from './common';

export interface DocSpec { type: string; label: string; hint: string; required: boolean }

/** কোন কাগজ লাগে — সার্ভারের lib/verify.js DOCS.badge-এর সাথে মেলানো */
export function badgeDocs(type: string): DocSpec[] {
  const idcard = { type: 'idcard', label: tr('Workplace ID card', 'কর্মস্থলের পরিচয়পত্র'), hint: tr('If you have it', 'থাকলে দিন'), required: false };
  if (type === 'doctor') {
    return [{ type: 'cert', label: tr('BMDC registration certificate', 'BMDC নিবন্ধন সনদ'), hint: tr('Optional — the number is checked online', 'থাকলে দিন — নম্বর অনলাইনে মিলিয়ে দেখা হয়'), required: false }, idcard];
  }
  if (type === 'nurse') {
    return [{ type: 'cert', label: tr('BNMC registration certificate', 'নার্সিং ও মিডওয়াইফারি কাউন্সিলের (BNMC) নিবন্ধন সনদ'), hint: tr('Number and name readable', 'নম্বর আর নাম পড়া যায়'), required: true }, idcard];
  }
  return [{ type: 'cert', label: tr('Certificate or degree', 'পেশার সনদ বা ডিগ্রির কাগজ'), hint: tr('Name and institution readable', 'নাম আর প্রতিষ্ঠান পড়া যায়'), required: true }, idcard];
}
function missingDocs(specs: DocSpec[], photos: Record<string, string>): string | null {
  const miss = specs.filter((d) => d.required && !photos[d.type]);
  return miss.length ? tr('Add photos of: ', 'এই কাগজের ছবি দিন: ') + miss.map((d) => d.label).join(', ') : null;
}

/** কাগজের ছবি বাছাই: প্রতিটার পাশে "ছবি দিন" আর ছোট ছবি (অ্যাপের DocPhotosField) */
export function DocPhotosField({ specs, photos, onChange, note }: { specs: DocSpec[]; photos: Record<string, string>; onChange: (p: Record<string, string>) => void; note?: string | null }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const picking = useRef<string | null>(null);
  return (
    <div className="col">
      <span className="t-label-l">{tr('Document photos', 'কাগজের ছবি')}</span>
      <span className="t-body-s muted">{note ?? tr('Clear photo of the whole paper.', 'পুরো কাগজের পরিষ্কার ছবি দিন।')}</span>
      <input
        ref={input}
        type="file"
        accept="image/*"
        hidden
        onChange={async (e) => {
          const f = e.target.files?.[0];
          const type = picking.current;
          e.target.value = '';
          if (!f || !type) return;
          setBusy(type);
          setErr(null);
          try {
            onChange({ ...photos, [type]: await toDocumentBase64(f) });
          } catch (x) {
            setErr(errorText(x));
          }
          setBusy(null);
        }}
      />
      {specs.map((d) => (
        <div key={d.type} className="row health-doc">
          {photos[d.type] ? <img src={imgSrc(photos[d.type])} alt="" className="health-doc-thumb" /> : <span className="health-doc-thumb empty"><Icon name="photo_camera" /></span>}
          <div className="col gap-2 grow">
            <span className="t-body-m semi">{d.label}{d.required ? ' *' : ''}</span>
            <span className="t-body-s muted">{d.hint}</span>
          </div>
          <Button
            variant="text"
            small
            busy={busy === d.type}
            disabled={busy != null}
            onClick={() => {
              picking.current = d.type;
              input.current?.click();
            }}
          >
            {photos[d.type] ? tr('Change', 'বদলান') : tr('Add photo', 'ছবি দিন')}
          </Button>
        </div>
      ))}
      <ErrorText>{err}</ErrorText>
    </div>
  );
}

function statusLine(b: { status: string; type: string; changePending?: boolean }): { text: string; tone?: 'good' | 'error' | 'info' } {
  if (b.status === 'approved') return { text: badgeLabel(b.type) + (b.changePending ? tr(' · correction under review', ' · সংশোধন যাচাই চলছে') : ''), tone: 'good' };
  if (b.status === 'pending') return { text: tr('Under review', 'যাচাই চলছে'), tone: 'info' };
  if (b.status === 'rejected') return { text: tr('Not approved. Tap to apply again.', 'অনুমোদন হয়নি। আবার আবেদন করুন।'), tone: 'error' };
  return { text: b.status };
}

/** অ্যাডমিন ছবি চাইলে বা দরকারি ছবি বাকি থাকলে (অ্যাপের DocsNeededNotice) */
function DocsNeeded({ uid, type, requested, note, onDone }: { uid: string; type: string; requested: boolean; note: string; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [photos, setPhotos] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  return (
    <div className="card tonal col">
      <span className="t-title-s">{requested ? tr('The Lifolo team asked for document photos', 'Lifolo টিম কাগজের ছবি চেয়েছে') : tr('Document photos are needed for checking', 'যাচাইয়ের জন্য কাগজের ছবি লাগবে')}</span>
      {note ? <p className="t-body-m pre">{note}</p> : null}
      {!open ? (
        <div><Button variant="outline" small onClick={() => setOpen(true)}>{tr('Add photos', 'ছবি দিন')}</Button></div>
      ) : (
        <>
          <DocPhotosField specs={badgeDocs(type)} photos={photos} onChange={setPhotos} />
          <ErrorText>{err}</ErrorText>
          <div className="row">
            <Button
              variant="outline"
              small
              busy={busy}
              disabled={!Object.keys(photos).length}
              onClick={async () => {
                setBusy(true);
                setErr(null);
                try {
                  await call('verify_docs_upload', { kind: 'badge', id: uid, docs: photos });
                  setOpen(false);
                  setPhotos({});
                  onDone();
                } catch (e) {
                  setErr(errorText(e));
                }
                setBusy(false);
              }}
            >
              {tr('Send', 'পাঠান')}
            </Button>
            <Button variant="text" small onClick={() => setOpen(false)}>{tr('Cancel', 'বাতিল')}</Button>
          </div>
        </>
      )}
    </div>
  );
}

export function BadgePage() {
  const nav = useNavigate();
  const s = useSession();
  const me = useMe();
  const back = () => goBack(nav, '/profile');
  const cur = me?.badge ?? null;
  const correction = cur?.status === 'approved';
  const [type, setType] = useState<string | null>(null);
  const [regNo, setRegNo] = useState<string | null>(null);
  const [workplace, setWorkplace] = useState<string | null>(null);
  const [docs, setDocs] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const t = type ?? cur?.type ?? 'doctor';
  const reg = regNo ?? cur?.regNo ?? '';
  const work = workplace ?? cur?.workplace ?? '';
  const title = correction ? tr('Correct badge details', 'ব্যাজের তথ্য সংশোধন') : tr('Professional badge', 'পেশাদার ব্যাজ');

  if (s.guest) {
    return (
      <FormPage title={tr('Professional badge', 'পেশাদার ব্যাজ')} onBack={back}>
        <EmptyState icon="workspace_premium" title={tr('Professional badge', 'পেশাদার ব্যাজ')} text={tr('For doctors, nurses and health experts', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞদের জন্য')}>
          <Button onClick={s.login}>{tr('Log in', 'লগইন')}</Button>
        </EmptyState>
      </FormPage>
    );
  }
  if (!me) {
    return (
      <FormPage title={title} onBack={back}>
        <Loading />
      </FormPage>
    );
  }

  const locked = cur?.status === 'pending' || !!cur?.changePending;
  const st = cur ? statusLine(cur) : null;
  const reason = cur && ['rejected', 'suspended'].includes(cur.status) ? String(cur.statusReason || '') : '';
  const docsMissing = !!cur?.docsMissing && cur?.status === 'pending';
  const docsRequested = !!cur?.docsRequested;

  async function submit() {
    if (!correction) {
      const m = missingDocs(badgeDocs(t), docs);
      if (m) {
        setErr(m);
        return;
      }
    }
    setBusy(true);
    setErr(null);
    try {
      await call('badge_apply', { type: t, regNo: reg.trim(), workplace: work.trim(), docs });
      setDocs({});
      setInfo(correction ? tr('Sent. An admin will check it.', 'পাঠানো হয়েছে। অ্যাডমিন যাচাই করবেন।') : tr('Applied. An admin will verify it.', 'আবেদন হয়েছে। অ্যাডমিন যাচাই করবেন।'));
      await s.reloadMe();
    } catch (e) {
      setErr(errorText(e));
    }
    setBusy(false);
  }

  return (
    <FormPage title={title} onBack={back}>
      {cur && st ? (
        <div className="card col">
          <div className="row">
            <span className="avatar" style={{ width: 40, height: 40 }}><Icon name={badgeIcon(cur.type)} size={20} /></span>
            <div className="col gap-2 grow">
              <span className="t-title-s">{tr('Professional badge', 'পেশাদার ব্যাজ')}</span>
              <span><Tag tone={st.tone}>{st.text}</Tag></span>
            </div>
          </div>
          {cur.regNo || cur.workplace ? (
            <div className="col gap-4">
              {cur.regNo ? <IconLine icon="badge">{cur.regNo}</IconLine> : null}
              {cur.workplace ? <IconLine icon="local_hospital">{cur.workplace}</IconLine> : null}
            </div>
          ) : null}
          {reason ? <IconLine icon="info">{tr('Reason: ', 'কারণ: ') + reason}</IconLine> : null}
        </div>
      ) : null}
      {cur && (docsMissing || docsRequested) && s.uid ? (
        <DocsNeeded uid={s.uid} type={cur.type} requested={docsRequested} note={String(cur.docsRequestNote || '')} onDone={() => s.reloadMe()} />
      ) : null}
      <InfoText>{info}</InfoText>
      {locked ? null : (
        <>
          {correction ? (
            <p className="t-body-s muted">
              {tr(
                'Fix your registration number or workplace. To fix your name, change it in Edit profile first, then send this. You stay listed while an admin checks.',
                'রেজিস্ট্রেশন নম্বর বা কর্মস্থল ঠিক করুন। নাম ঠিক করতে আগে "প্রোফাইল এডিট"-এ নাম বদলে তারপর এটা পাঠান। অ্যাডমিন দেখা পর্যন্ত আপনি আগের মতোই তালিকায় থাকবেন।',
              )}
            </p>
          ) : null}
          <div className="field">
            <span className="label">{tr('Profession', 'পেশা')}</span>
            <div className="chips" role="group" aria-label={tr('Profession', 'পেশা')}>
              {BADGES.map((b) => (
                <button key={b} type="button" className="chip lead" aria-pressed={t === b} disabled={correction && t !== b} onClick={() => setType(b)}>
                  <Icon name={badgeIcon(b)} />
                  {badgeLabel(b)}
                </button>
              ))}
            </div>
          </div>
          <TextInput label={tr('Registration / licence no.', 'রেজিস্ট্রেশন / লাইসেন্স নম্বর')} value={reg} maxLength={60} placeholder={t === 'doctor' ? 'BMDC A-12345' : ''} onChange={(e) => setRegNo(e.target.value)} />
          <TextInput label={tr('Workplace', 'কর্মস্থল')} value={work} maxLength={120} onChange={(e) => setWorkplace(e.target.value)} />
          <DocPhotosField
            specs={badgeDocs(t)}
            photos={docs}
            onChange={setDocs}
            note={correction ? tr('Add a photo if your registration number changed.', 'রেজিস্ট্রেশন নম্বর বদলালে সনদের ছবি দিন।') : null}
          />
          <p className="t-body-s muted">{tr('An admin will verify this before the badge appears.', 'অ্যাডমিন যাচাই করার পর ব্যাজ দেখা যাবে।')}</p>
          <ErrorText>{err}</ErrorText>
          <div className="form-actions">
            <Button variant="text" disabled={busy} onClick={back}>{tr('Cancel', 'বাতিল')}</Button>
            <Button busy={busy} onClick={submit}>{correction ? tr('Send', 'পাঠান') : tr('Apply', 'আবেদন করুন')}</Button>
          </div>
        </>
      )}
    </FormPage>
  );
}
