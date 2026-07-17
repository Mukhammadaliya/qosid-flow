import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { opsApi, requestHandoff } from './ops';

describe('opsApi CSRF interceptor', () => {
  beforeEach(() => { document.cookie = 'OPS-XSRF-TOKEN=tok123'; });
  afterEach(() => { document.cookie = 'OPS-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('echoes OPS-XSRF-TOKEN cookie in X-OPS-XSRF-TOKEN header', async () => {
    const handler = opsApi.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: { headers: Record<string, string> }) => { headers: Record<string, string> } }>;
    };
    const config = handler.handlers[0].fulfilled({ headers: {} });
    expect(config.headers['X-OPS-XSRF-TOKEN']).toBe('tok123');
  });

  it('does not set the gateway header name', async () => {
    const handler = opsApi.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: { headers: Record<string, string> }) => { headers: Record<string, string> } }>;
    };
    const config = handler.handlers[0].fulfilled({ headers: {} });
    expect(config.headers['X-XSRF-TOKEN']).toBeUndefined();
  });
});

describe('requestHandoff', () => {
  it('posts to /ops/api/handoff and returns the one-time token', async () => {
    const post = vi.spyOn(opsApi, 'post').mockResolvedValue({ data: { token: 't-abc' } } as never);
    const r = await requestHandoff();
    expect(post).toHaveBeenCalledWith('/ops/api/handoff');
    expect(r.data.token).toBe('t-abc');
    post.mockRestore();
  });
});
