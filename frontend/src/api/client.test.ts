import { describe, it, expect, vi, afterEach } from 'vitest';
import { api, setUnauthorizedHandler } from './client';

describe('api client', () => {
  afterEach(() => { document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('copies the XSRF-TOKEN cookie into the X-XSRF-TOKEN header', async () => {
    document.cookie = 'XSRF-TOKEN=tok-42';
    const cfg = await (api.interceptors.request as any).handlers[0].fulfilled({ headers: {} });
    expect(cfg.headers['X-XSRF-TOKEN']).toBe('tok-42');
  });

  it('invokes the unauthorized handler on a 401 response', async () => {
    const spy = vi.fn();
    setUnauthorizedHandler(spy);
    const rejected = (api.interceptors.response as any).handlers[0].rejected;
    await rejected({ response: { status: 401 } }).catch(() => {});
    expect(spy).toHaveBeenCalledOnce();
  });
});
