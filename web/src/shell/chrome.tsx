// পাতা থেকে খোলস (shell)-কে জানানো: শিরোনাম, আর মোবাইলে বিস্তারিত পাতা খোলা কি না
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

interface Chrome {
  title: string;
  setTitle: (t: string) => void;
  detailOpen: number;
  setDetailOpen: (f: (n: number) => number) => void;
  subOpen: number;
  setSubOpen: (f: (n: number) => number) => void;
}
const Ctx = createContext<Chrome | null>(null);

export function ChromeProvider({ children }: { children: ReactNode }) {
  const [title, setTitle] = useState('');
  const [detailOpen, setDetailOpen] = useState(0);
  const [subOpen, setSubOpen] = useState(0);
  return <Ctx.Provider value={{ title, setTitle, detailOpen, setDetailOpen, subOpen, setSubOpen }}>{children}</Ctx.Provider>;
}
export function useChrome() {
  const c = useContext(Ctx);
  if (!c) throw new Error('ChromeProvider missing');
  return c;
}

/** পাতার শিরোনাম (উপরের বার আর ব্রাউজারের ট্যাবে) */
export function usePageTitle(title: string) {
  const { setTitle } = useChrome();
  useEffect(() => {
    setTitle(title);
    document.title = title ? `${title} · Lifolo` : 'Lifolo';
  }, [title, setTitle]);
}

/** মোবাইলে বিস্তারিত পাতা: উপরের বার আর নিচের ট্যাব লুকায় (অ্যাপের মতো পুরো পাতা) */
export function useDetailMode(active: boolean) {
  const { setDetailOpen } = useChrome();
  useEffect(() => {
    if (!active) return;
    setDetailOpen((n) => n + 1);
    return () => setDetailOpen((n) => n - 1);
  }, [active, setDetailOpen]);
}

/** চওড়া পর্দা (দুই ভাগ দেখা যায়) কি না */
export function useMedia(query: string) {
  const [m, setM] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const f = () => setM(mq.matches);
    f();
    mq.addEventListener('change', f);
    return () => mq.removeEventListener('change', f);
  }, [query]);
  return m;
}
/** ফর্ম/সাব-পাতা: বড় পর্দায় উপরের বারের শিরোনাম লুকায় (পাতার নিজের শিরোনাম থাকে) */
export function useSubPage() {
  const { setSubOpen } = useChrome();
  useEffect(() => {
    setSubOpen((n) => n + 1);
    return () => setSubOpen((n) => n - 1);
  }, [setSubOpen]);
}
export const useTwoPane = () => useMedia('(min-width: 960px)');
export const useMobile = () => useMedia('(max-width: 719px)');
