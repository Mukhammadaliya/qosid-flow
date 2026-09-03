import { api } from './client';
import { opsApi } from './ops';
import { isGatewayReachable } from './gatewayReach';

/**
 * Prometheus reads. Same rule as {@link ./logs}: Prometheus keeps scraping and keeps its
 * series when the gateway JVM is stopped, so the Statistics dashboard falls back to the ops
 * sidecar's twin route rather than going blank.
 */
const GATEWAY_BASE = '/qosid/admin/api/stats';
const OPS_BASE = '/ops/api/stats';

const route = () =>
  isGatewayReachable()
    ? { client: api, base: GATEWAY_BASE }
    : { client: opsApi, base: OPS_BASE };

// Backend serializes the record StatPoint(double t, double v) as {"t":..,"v":..}
export interface StatPoint { t: number; v: number } // t = epoch SECONDS, v = value
export interface StatSeries { name: string; points: StatPoint[] }
export interface StatsResult { series: StatSeries[]; kpis: Record<string, number | null>; error?: string }

export interface StatsParams { from?: number; to?: number; step?: number }

export const queryStats = (preset: string, params: StatsParams = {}) => {
  const { client, base } = route();
  return client.get<StatsResult>(base, { params: { preset, ...params } }).then(r => r.data);
};
