import { describe, expect, it } from 'vitest';
import { matchPath, userState } from '@/lib/nav';

describe('matchPath (React Router-এর মতো)', () => {
  it('পুরো পথ মেলে, প্যারামিটার আসে', () => {
    expect(matchPath('/work/lab/orders/:id', '/work/lab/orders/abc123')).toEqual({ params: { id: 'abc123' } });
    expect(matchPath('/work/lab/orders/:id', '/work/lab/orders/abc/x')).toBeNull();
  });
  it('শেষের / চলে, বড়-ছোট অক্ষর একই', () => {
    expect(matchPath('/services', '/Services/')).not.toBeNull();
  });
  it('end: false — শুরুটা মিললেই', () => {
    expect(matchPath({ path: '/work', end: false }, '/work/pharmacy/shop')).not.toBeNull();
    expect(matchPath({ path: '/work', end: false }, '/workshop')).toBeNull();
  });
  it('* — বাকি পথ', () => {
    expect(matchPath('/rx/*', '/rx/write/new')).toEqual({ params: { '*': 'write/new' } });
    expect(matchPath('/rx/*', '/rx')).toEqual({ params: { '*': '' } });
  });
  it('এনকোড করা প্যারামিটার খোলে', () => {
    expect(matchPath('/r/:id', '/r/a%20b')?.params.id).toBe('a b');
  });
});

describe('userState', () => {
  it('Vue Router-এর নিজের চাবি বাদ', () => {
    expect(userState({ back: '/x', current: '/y', position: 3, flash: 'ok' })).toEqual({ flash: 'ok' });
    expect(userState({ back: null, current: '/y' })).toBeNull();
    expect(userState(null)).toBeNull();
  });
});
