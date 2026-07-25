import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosHeaders } from 'axios';
import { opsApi } from './ops';
import { opsSettings } from './opsSettings';

/** Stub the axios adapter on the shared opsApi instance so we can assert the
 *  exact method/URL/headers/body each opsSettings call produces. The request
 *  interceptor chain (OPS CSRF header echo) still runs before the adapter. */
type CapturedConfig = { method?: string; url?: string; data?: string; headers: AxiosHeaders };

function stubAdapter(data: unknown) {
  const captured: CapturedConfig[] = [];
  opsApi.defaults.adapter = vi.fn(async (config: CapturedConfig) => {
    captured.push(config);
    return { data, status: 200, statusText: 'OK', headers: {}, config };
  }) as never;
  return captured;
}

const originalAdapter = opsApi.defaults.adapter;

describe('opsSettings api', () => {
  beforeEach(() => { document.cookie = 'OPS-XSRF-TOKEN=ops-tok-7'; });
  afterEach(() => {
    opsApi.defaults.adapter = originalAdapter;
    document.cookie = 'OPS-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('getAll fetches GET /ops/api/settings and returns the array', async () => {
    const rows = [{ key: 'k', value: 'v', secret: false, group: 'G', label: 'L', type: 'string', editable: true }];
    const captured = stubAdapter(rows);
    const result = await opsSettings.getAll();
    expect(result).toEqual(rows);
    expect(captured[0].method).toBe('get');
    expect(captured[0].url).toBe('/ops/api/settings');
  });

  it('put sends PUT /ops/api/settings/{key} with {value} body and the OPS CSRF header', async () => {
    const captured = stubAdapter({ key: 'gateway.api.base-url', status: 'SAVED' });
    await opsSettings.put('gateway.api.base-url', 'https://b.example.com');
    expect(captured[0].method).toBe('put');
    expect(captured[0].url).toBe('/ops/api/settings/gateway.api.base-url');
    expect(JSON.parse(captured[0].data as string)).toEqual({ value: 'https://b.example.com' });
    expect(captured[0].headers.get('X-OPS-XSRF-TOKEN')).toBe('ops-tok-7');
  });

  it('put URL-encodes the key', async () => {
    const captured = stubAdapter({ key: 'a b/c', status: 'SAVED' });
    await opsSettings.put('a b/c', 'x');
    expect(captured[0].url).toBe('/ops/api/settings/a%20b%2Fc');
  });

  it('getAudit fetches GET /ops/api/settings/audit', async () => {
    const rows = [{ skey: 'k', old_value: 'a', new_value: 'b', changed_by: 'admin', changed_at: '2026-07-17' }];
    const captured = stubAdapter(rows);
    const result = await opsSettings.getAudit();
    expect(result).toEqual(rows);
    expect(captured[0].method).toBe('get');
    expect(captured[0].url).toBe('/ops/api/settings/audit');
  });
});
