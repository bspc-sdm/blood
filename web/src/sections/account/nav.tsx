// পেছনে যাওয়া (সরাসরি লিংকে খুললে আগের পাতা না থাকলে নির্দিষ্ট পাতায়) আর বাইরের লিংকের সারি
import { useCallback, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@/ui/Icon';

export function useBack(fallback: string) {
  const nav = useNavigate();
  return useCallback(() => {
    const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0;
    if (idx > 0) nav(-1);
    else nav(fallback, { replace: true });
  }, [nav, fallback]);
}

/** নতুন ট্যাবে খোলে এমন সারি (প্রাইভেসি পলিসি, শর্ত) */
export function LinkRow({ icon, title, sub, href }: { icon: string; title: ReactNode; sub?: ReactNode; href: string }) {
  return (
    <a className="list-row" href={href} target="_blank" rel="noopener noreferrer">
      <Icon name={icon} />
      <span className="col gap-2 grow">
        <span className="t-body-l">{title}</span>
        {sub ? <span className="sub">{sub}</span> : null}
      </span>
      <Icon name="chevron_right" className="chev" />
    </a>
  );
}
