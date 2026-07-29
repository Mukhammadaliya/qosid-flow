import axios from 'axios';
import { getCookie } from './cookies';

/**
 * Ops sidecar client. Separate axios instance: ops uses renamed cookies
 * (OPS_SESSION / OPS-XSRF-TOKEN) so it can coexist with the gateway on one origin.
 * NOTE: deliberately NO global 401 handler here — degraded-mode logic in
 * AuthContext interprets ops failures itself.
 */
export const opsApi = axios.create({ withCredentials: true });

opsApi.interceptors.request.use((config) => {
  const token = getCookie('OPS-XSRF-TOKEN');
  if (token) config.headers['X-OPS-XSRF-TOKEN'] = token;
  return config;
});

export interface ControlResult { ok: boolean; exitCode: number; output: string; }

/**
 * Shared shape of the ops /login and /me bodies. {@code passwordChangeRequired} is a string
 * because the backend body is a Map<String, String> — same contract as the gateway's, and the
 * only source of the hint while the gateway is stopped.
 */
export interface OpsSessionBody { passwordChangeRequired?: string; }

const BASE = '/ops/api';

export const opsLogin = (username: string, password: string) =>
  opsApi.post<OpsSessionBody>(`${BASE}/login`, { username, password });
export const opsLogout = () => opsApi.post<void>(`${BASE}/logout`);
/**
 * Ops-side password change. Needed as well as the gateway's: in the normal stop-to-edit state
 * the gateway is down, so its change-password route is unreachable and this is the only way out
 * of the default-password gate.
 */
export const opsChangePassword = (oldPassword: string, newPassword: string) =>
  opsApi.post<void>(`${BASE}/change-password`, { oldPassword, newPassword }).then(() => undefined);
export const opsMe = () => opsApi.get<OpsSessionBody & { user: string }>(`${BASE}/me`);
export const getOpsStatus = () => opsApi.get<{ up: boolean }>(`${BASE}/status`);
export const startGateway = () => opsApi.post<ControlResult>(`${BASE}/start`);
export const stopGateway = () => opsApi.post<ControlResult>(`${BASE}/stop`);
/** One-time login handoff token (60s TTL) — exchanged for a gateway session via /login-token. */
export const requestHandoff = () => opsApi.post<{ token: string }>(`${BASE}/handoff`);
