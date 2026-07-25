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

const BASE = '/ops/api';

export const opsLogin = (username: string, password: string) =>
  opsApi.post<void>(`${BASE}/login`, { username, password });
export const opsLogout = () => opsApi.post<void>(`${BASE}/logout`);
export const opsMe = () => opsApi.get<{ user: string }>(`${BASE}/me`);
export const getOpsStatus = () => opsApi.get<{ up: boolean }>(`${BASE}/status`);
export const startGateway = () => opsApi.post<ControlResult>(`${BASE}/start`);
export const stopGateway = () => opsApi.post<ControlResult>(`${BASE}/stop`);
/** One-time login handoff token (60s TTL) — exchanged for a gateway session via /login-token. */
export const requestHandoff = () => opsApi.post<{ token: string }>(`${BASE}/handoff`);
