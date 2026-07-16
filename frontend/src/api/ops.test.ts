import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { opsApi } from './ops';

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
