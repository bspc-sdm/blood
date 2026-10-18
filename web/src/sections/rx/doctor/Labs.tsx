// ডাক্তারের রোগীর ইতিহাসে আর প্রেসক্রিপশনের পাতায় পার্টনার ল্যাবের পরীক্ষা (১৮ অক্টোবর ২০২৬, "ডাক্তারকে রিপোর্ট দেখানো")।
// সার্ভারের doctorLabView: অবস্থা সবসময়; ফল শুধু রিপোর্ট দেখার টাকা মিললে বা "ফি চাই না" হলে (open) — নইলে কেন বন্ধ।
// পুরো রিপোর্ট (ছবি, আগের ফল) lab_order_get দিয়ে, Reviews.tsx-এর LabReportView। অ্যাপের DoctorLabList (RxNet.kt)।
import { useState } from 'react';
import { Action, Button, Dialog, ErrorText, Loading, Tag } from '@/ui/kit';
import { Icon } from '@/ui/Icon';
import { useCall } from '@/lib/query';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { SELF, flagLabel, labLocked, labStatus, type DoctorLab, type LabHistory, type LabOrder } from './model';
import { LabReportView } from './Reviews';

/** এক প্রেসক্রিপশনের (বা কয়েকটার) ল্যাব অর্ডার — নতুন আগে */
export function DoctorLabList({ labs }: { labs: DoctorLab[] }) {
  const [reading, setReading] = useState<DoctorLab | null>(null);
  if (!labs.length) return null;
  return (
    <div className="col gap-6 rxd-labs">
      {labs.map((o) => {
        const items = o.report?.items ?? [];
        const flagged = items.filter((it) => it.flag && !it.long);
        return (
          <div key={o.id} className="col gap-2 rxd-lab">
            <div className="row top gap-6">
              <span className="t-body-l semi grow">{o.labName || tr('Lab', 'প্যাথলজি')}</span>
              <Tag tone={o.status === 'reported' ? 'good' : o.status === 'cancelled' ? 'error' : undefined}>{labStatus(o.status)}</Tag>
            </div>
            <span className="t-body-s muted">{[o.tests.join(', '), formatDate(o.reportedAt || o.createdAt)].filter(Boolean).join(' · ')}</span>
            {o.status === 'reported' && o.open ? (
              <>
                {flagged.length ? (
                  <span className="t-body-s c-error">
                    {tr('High/low: ', 'বেশি/কম: ')
                      + flagged.slice(0, 4).map((it) => `${it.test} ${it.result}${it.unit ? ' ' + it.unit : ''} (${flagLabel(it.flag)})`).join(', ')
                      + (flagged.length > 4 ? ' +' + num(flagged.length - 4) : '')}
                  </span>
                ) : items.length ? (
                  <span className="t-body-s muted">{tr(`${items.length} result(s)`, `${num(items.length)}টা ফল`)}</span>
                ) : null}
                {o.review === 'paid' ? <span className="t-body-s muted">{tr('Not replied yet — reply from Reports', 'রোগীকে এখনো জানাননি — "রিপোর্ট" থেকে জানান')}</span> : null}
                {o.comment ? <span className="t-body-s pre">{tr('You told the patient: ', 'রোগীকে জানিয়েছিলেন: ') + o.comment}</span> : null}
                <div><Action primary icon="list_alt" onClick={() => setReading(o)}>{tr('See report', 'রিপোর্ট দেখুন')}</Action></div>
              </>
            ) : o.status === 'reported' ? (
              <span className="row gap-6 t-body-s muted rxd-lab-locked"><Icon name="privacy_tip" />{labLocked(o.review)}</span>
            ) : null}
          </div>
        );
      })}
      {reading ? <DoctorReportDialog o={reading} onClose={() => setReading(null)} /> : null}
    </div>
  );
}

/** পুরো রিপোর্ট: ফল, আগের ফল পাশে, নোট, ছবি (রিপোর্ট দেখার পাতার একই ছক) */
function DoctorReportDialog({ o, onClose }: { o: DoctorLab; onClose: () => void }) {
  const q = useCall<{ item: LabOrder; history?: LabHistory }>('lab_order_get', { id: o.id, ...SELF });
  return (
    <Dialog
      onClose={onClose}
      size="wide"
      title={tr('Test report', 'পরীক্ষার রিপোর্ট')}
      actions={<Button variant="text" onClick={onClose}>{tr('Close', 'বন্ধ')}</Button>}
    >
      {q.isPending ? <Loading /> : q.isError ? <ErrorText>{errorText(q.error)}</ErrorText> : <LabReportView o={q.data.item} history={q.data.history ?? {}} />}
    </Dialog>
  );
}
