import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosHeaders } from 'axios';
import { api } from './client';
import { opsApi } from './ops';
import { setGatewayReachable } from './gatewayReach';
import { queryLogs, getLabelValues } from './logs';
import { queryStats } from './stats';

/**
 * The Errors and Statistics modules must keep reading Loki and Prometheus while the gateway
 * JVM is stopped. Both backends expose the same query surface, so the only thing that changes
 * is which one is asked: the gateway's admin API when it is up, the ops sidecar when it is not.
 */
type Captured = { method?: string; url?: string; params?: unknown; headers: AxiosHeaders };

function stub(instance: typeof api, data: unknown) {
  const captured: Captured[] = [];
  instance.defaults.adapter = vi.fn(async (config: Captured) => {
    captured.push(config);
    return { data, status: 200, statusText: 'OK', headers: {}, config };
  }) as never;
  return captured;
}

const originalApi = api.defaults.adapter;
const originalOps = opsApi.defaults.adapter;

describe('observability route selection', () => {
  beforeEach(() => setGatewayReachable(true));
  afterEach(() => {
    api.defaults.adapter = originalApi;
    opsApi.defaults.adapter = originalOps;
    setGatewayReachable(true);
  });

  it('queries the gateway admin API while the gateway is reachable', async () => {
    const gw = stub(api, { entries: [], error: null });
    await queryLogs({ source: 'dlq' });
    expect(gw[0].url).toBe('/qosid/admin/api/logs');
  });

  it('queries the ops sidecar for logs once the gateway is unreachable', async () => {
    const ops = stub(opsApi, { entries: [], error: null });
    setGatewayReachable(false);
    await queryLogs({ source: 'dlq' });
    expect(ops[0].url).toBe('/ops/api/logs');
  });

  it('queries the ops sidecar for label values once the gateway is unreachable', async () => {
    const ops = stub(opsApi, { values: ['redpanda'] });
    setGatewayReachable(false);
    const values = await getLabelValues('container');
    expect(ops[0].url).toBe('/ops/api/logs/labels');
    expect(values).toEqual(['redpanda']);
  });

  it('queries the ops sidecar for stats once the gateway is unreachable', async () => {
    const ops = stub(opsApi, { series: [], kpis: {}, error: null });
    setGatewayReachable(false);
    await queryStats('kpi');
    expect(ops[0].url).toBe('/ops/api/stats');
  });

  it('keeps stats on the gateway admin API while the gateway is reachable', async () => {
    const gw = stub(api, { series: [], kpis: {}, error: null });
    await queryStats('kpi');
    expect(gw[0].url).toBe('/qosid/admin/api/stats');
  });

  it('sends the same query parameters whichever backend answers', async () => {
    const ops = stub(opsApi, { entries: [], error: null });
    setGatewayReachable(false);
    await queryLogs({ source: 'docker', container: 'redpanda', level: 'error', limit: 50 });
    expect(ops[0].params).toMatchObject({ source: 'docker', container: 'redpanda', level: 'error', limit: 50 });
  });
});
