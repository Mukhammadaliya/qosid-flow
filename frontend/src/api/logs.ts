import { api } from './client';
import { opsApi } from './ops';
import { isGatewayReachable } from './gatewayReach';

/**
 * Loki reads. Both backends expose the identical query surface, so the module only picks
 * which one to ask: the gateway's admin API while it is up, the ops sidecar once it is not.
 * Loki itself is a separate process that outlives the gateway, so a stopped gateway must not
 * blank the Errors module — see OpsObservabilityController.
 *
 * The ops instance carries its own cookies (OPS_SESSION) and deliberately has no global 401
 * interceptor, which is what we want here: a 401 from ops must not trigger the gateway's
 * session-refresh path.
 */
const GATEWAY_BASE = '/qosid/admin/api/logs';
const OPS_BASE = '/ops/api/logs';

const route = () =>
  isGatewayReachable()
    ? { client: api, base: GATEWAY_BASE }
    : { client: opsApi, base: OPS_BASE };

export interface LogEntry {
  timestamp: string;
  level: string;
  line: string;
  labels: Record<string, string>;
  parsed?: any;
}
export interface LogQueryResult { entries: LogEntry[]; error?: string }

export interface LogQueryParams {
  source: 'dlq' | 'jar' | 'docker';
  from?: number;
  to?: number;
  q?: string;
  container?: string;
  level?: string;
  limit?: number;
}

export const queryLogs = (params: LogQueryParams) => {
  const { client, base } = route();
  return client.get<LogQueryResult>(base, { params }).then(r => r.data);
};

export const getLabelValues = (name: string) => {
  const { client, base } = route();
  return client.get<{ values: string[] }>(`${base}/labels`, { params: { name } }).then(r => r.data.values);
};
