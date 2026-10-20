import { describe, expect, it } from 'vitest';
import type { ErrorEvent } from '@sentry/vue';
import { scrub, scrubEvent } from '../../src/lib/sentry';

describe('sentry scrub', () => {
  it('hides emails, phones and tokens', () => {
    expect(scrub('user a.b@x.com failed')).toBe('user [email] failed');
    expect(scrub('call 01712345678 or +8801712345678')).toBe('call [phone] or [phone]');
    expect(scrub('Authorization: Bearer abc.def-123')).toContain('[');
    expect(scrub('Bearer abcdef123')).toBe('Bearer [token]');
    expect(scrub('t=eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl')).toBe('t=[token]');
    expect(scrub('?token=s3cr3t&x=1')).toBe('?token=[filtered]&x=1');
    expect(scrub('order 12345 at 10:30')).toBe('order 12345 at 10:30');
  });
  it('drops user, cookies and body', () => {
    const e = scrubEvent({
      type: undefined,
      user: { email: 'a@b.co' },
      message: 'x a@b.co',
      request: { url: 'https://lifolo.com/p?email=a@b.co', cookies: { s: '1' }, data: { phone: '01712345678' }, headers: { Cookie: 'x', 'User-Agent': 'UA' } },
      exception: { values: [{ type: 'Error', value: 'bad 01812345678' }] },
    } as ErrorEvent);
    expect(e.user).toBeUndefined();
    expect(e.request?.cookies).toBeUndefined();
    expect(e.request?.data).toBeUndefined();
    expect(e.request?.headers).toEqual({ 'User-Agent': 'UA' });
    expect(e.message).toBe('x [email]');
    expect(e.request?.url).not.toContain('a@b.co');
    expect(e.exception?.values?.[0]?.value).toBe('bad [phone]');
  });
});
