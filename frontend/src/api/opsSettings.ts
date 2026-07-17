import { opsApi } from './ops';

/**
 * Settings editing via the ops sidecar (:8092) — only usable while the gateway
 * JVM is STOPPED (the sidecar answers 409 {"error":"service-running"} otherwise).
 * Rides the shared opsApi instance, so the OPS_SESSION cookie and the
 * X-OPS-XSRF-TOKEN CSRF header echo apply automatically.
 */

const BASE = '/ops/api';

export interface OpsSetting {
  key: string;
  value: string;
  secret: boolean;
  group: string;
  label: string;
  type: string;
  editable: boolean;
}

/** Audit rows come with the raw H2 column names (skey / changed_by — not key / author). */
export interface OpsAuditRow {
  skey: string;
  old_value: string;
  new_value: string;
  changed_by: string;
  changed_at: string;
}

export const opsSettings = {
  getAll: (): Promise<OpsSetting[]> =>
    opsApi.get<OpsSetting[]>(`${BASE}/settings`).then((r) => r.data),
  put: (key: string, value: string): Promise<void> =>
    opsApi.put(`${BASE}/settings/${encodeURIComponent(key)}`, { value }).then(() => undefined),
  getAudit: (): Promise<OpsAuditRow[]> =>
    opsApi.get<OpsAuditRow[]>(`${BASE}/settings/audit`).then((r) => r.data),
};
