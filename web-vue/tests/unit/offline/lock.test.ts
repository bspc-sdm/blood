// @vitest-environment node
// পিন লক (দুই স্তর, ১০ মিনিট, ভুলের শাস্তি, ১০ বারে মোছা) আর ৭ দিন / ঘড়ি (I12)
import { describe, expect, test } from 'vitest';
import { PinLock, IDLE_MS } from '@/offline/desktop/pin';
import { DAY, Grace } from '@/offline/desktop/grace';
import { scanDetector } from '@/offline/scanner';

function mem() {
  const m = new Map<string, string>();
  return { m, get: async (k: string) => m.get(k) ?? null, set: async (k: string, v: string) => void m.set(k, v) };
}

describe('পিন', () => {
  test('পিন না থাকলে লক নেই, মালিক; মালিকের পিন দিলে চালুর সময় লক', async () => {
    const st = mem();
    let t = 1_000_000;
    const p = new PinLock(st, async () => {}, () => t, 1000);
    await p.load('u');
    expect(p.state.locked).toBe(false);
    expect(p.canOwner()).toBe(true);
    await p.setPin('owner', '4321');
    expect([...st.m.values()][0]).not.toContain('4321');
    const p2 = new PinLock(st, async () => {}, () => t, 1000);
    await p2.load('u');
    expect(p2.state.locked).toBe(true);
    expect(await p2.unlock('4321')).toEqual({ ok: true, role: 'owner' });
    t += IDLE_MS - 1;
    p2.idleCheck();
    expect(p2.state.locked).toBe(false);
    t += 1;
    p2.idleCheck();
    expect(p2.state.locked).toBe(true);
  });

  test('কাউন্টার: বিক্রি পারে, মালিকের কাজে মালিকের পিন লাগে (৬০ সে)', async () => {
    let t = 0;
    const p = new PinLock(mem(), async () => {}, () => t, 1000);
    await p.load('u');
    await p.setPin('owner', '1111');
    await expect(p.setPin('counter', '1111')).rejects.toThrow();
    await p.setPin('counter', '2222');
    p.lock();
    expect(await p.unlock('2222')).toEqual({ ok: true, role: 'counter' });
    expect(p.canOwner()).toBe(false);
    const ask = p.requireOwner();
    expect(p.state.asking).toBe(true);
    expect((await p.answerOwner('2222')).ok).toBe(false); // কাউন্টারের পিন এখানে চলে না
    expect((await p.answerOwner('1111')).ok).toBe(true);
    expect(await ask).toBe(true);
    expect(p.canOwner()).toBe(true);
    t += 61_000;
    expect(p.canOwner()).toBe(false);
    const ask2 = p.requireOwner();
    p.cancelAsk();
    expect(await ask2).toBe(false);
  });

  test('৫ বার ভুলের পর অপেক্ষা, ১০ বার ভুলে সব মোছা', async () => {
    let t = 0;
    let wiped = 0;
    const p = new PinLock(mem(), async () => void wiped++, () => t, 1000);
    await p.load('u');
    await p.setPin('owner', '123456');
    p.lock();
    for (let i = 1; i <= 4; i++) expect(await p.unlock('000000')).toMatchObject({ ok: false, waitMs: 0, left: 10 - i });
    expect(await p.unlock('000000')).toMatchObject({ ok: false, waitMs: 30_000 });
    // অপেক্ষার মধ্যে ঠিক পিনও চলে না
    expect(await p.unlock('123456')).toMatchObject({ ok: false });
    for (let i = 6; i <= 9; i++) {
      t += 16 * 60_000;
      await p.unlock('000000');
    }
    expect(wiped).toBe(0);
    t += 16 * 60_000;
    expect(await p.unlock('000000')).toMatchObject({ ok: false, wiped: true });
    expect(wiped).toBe(1);
  });
});

describe('৭ দিন আর ঘড়ি', () => {
  test('অনলাইনে যাচাইয়ের ৭ দিন পর বন্ধ, ৬ দিনে সতর্কতা; অনলাইন হলে আবার চালু', async () => {
    let t = 10 * DAY;
    const g = new Grace(mem(), () => t);
    await g.load('u');
    expect(g.tick()).toBe('ok');
    g.online(t);
    t += 6 * DAY;
    expect(g.tick()).toBe('ok');
    expect(g.state.warn).toBe(true);
    t += DAY;
    expect(g.tick()).toBe('login_expired');
    g.online(t);
    expect(g.tick()).toBe('ok');
  });

  test('ঘড়ি পিছিয়ে দিন বাড়ানো যায় না; ১০ মিনিটের বেশি পিছালে "সময় ঠিক করুন"', async () => {
    let t = 100 * DAY;
    const st = mem();
    const g = new Grace(st, () => t);
    await g.load('u');
    g.online(t);
    t += 6.9 * DAY;
    g.tick();
    await g.save();
    t -= 5 * DAY; // ঘড়ি পেছানো
    expect(g.tick()).toBe('clock');
    // নতুন করে খুললেও (সংরক্ষিত maxSeen)
    const g2 = new Grace(st, () => t);
    await g2.load('u');
    expect(g2.tick()).toBe('clock');
    t += 5 * DAY + 0.2 * DAY; // ঠিক করলে — কিন্তু ৭ দিন পার
    expect(g2.tick()).toBe('login_expired');
  });
});

describe('বারকোড স্ক্যানার', () => {
  const key = (k: string) => ({ key: k, ctrlKey: false, altKey: false, metaKey: false, preventDefault() {}, stopPropagation() {}, target: null }) as unknown as KeyboardEvent;

  test('দ্রুত অক্ষর + Enter = স্ক্যান; ধীর টাইপ নয়', () => {
    let t = 0;
    const got: string[] = [];
    const h = scanDetector((c) => got.push(c), {}, () => t);
    for (const c of '8901234') {
      h(key(c));
      t += 10;
    }
    expect(h(key('Enter'))).toBe(true);
    for (const c of '1234') {
      h(key(c));
      t += 200;
    }
    expect(h(key('Enter'))).toBe(false);
    expect(got).toEqual(['8901234']);
  });
});
