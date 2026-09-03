import { createContext, useContext, useEffect, useState, ReactNode, useCallback, useRef } from 'react';
import axios from 'axios';
import { api, setUnauthorizedHandler } from '../api/client';
import { setGatewayReachable } from '../api/gatewayReach';
import { opsLogin, opsLogout, opsMe, requestHandoff } from '../api/ops';

export interface RefreshOptions { silent?: boolean; }

interface AuthState {
  user: string | null;
  loading: boolean;
  gatewayUp: boolean;
  opsUp: boolean;
  /** True only when the gateway /me probe reached the gateway and got 401'd (session expired) —
   * as opposed to the gateway being unreachable. gatewayUp is false in both cases. */
  gatewayAuthExpired: boolean;
  /** Hint from whichever backend answered (/login and /me on both): the admin password is still
   * the seeded default, so that backend 403s every route but login/me/logout/change-password/
   * health. The gateway's answer wins; ops is the fallback, and the only source while the gateway
   * is stopped. Enforcement is server-side (DefaultPasswordGateFilter / OpsDefaultPasswordGate
   * Filter) — this only drives the forced change form. */
  passwordChangeRequired: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** opts.silent skips the loading flag flip so RequireAuth's spinner doesn't unmount the
   * current page (used by the 401 interceptor and by background up/down transition checks). */
  refresh: (opts?: RefreshOptions) => Promise<void>;
  /** Avto-ulanish after a gateway start: mint a one-time token from ops and exchange it for a
   * gateway session, then silently re-probe. Any failure is swallowed — worst case the user
   * stays in degraded mode exactly as before this hook existed. */
  attemptHandoff: () => Promise<void>;
}

export const AuthContext = createContext<AuthState>(null as unknown as AuthState);
export const useAuth = () => useContext(AuthContext);

const BASE = '/qosid/admin/api';

/** 401 => wrong credentials / no session; anything else rejected => backend unreachable. */
function is401(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 401;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [gatewayUp, setGatewayUp] = useState(false);
  const [opsUp, setOpsUp] = useState(false);
  const [gatewayAuthExpired, setGatewayAuthExpired] = useState(false);
  const [passwordChangeRequired, setPasswordChangeRequired] = useState(false);
  // Re-entrancy guard: the 401 interceptor calls refresh(), whose own /me probe can itself
  // 401 and re-fire the interceptor — without this, that's an unbounded network loop.
  const refreshing = useRef(false);

  const refresh = useCallback(async (opts?: RefreshOptions) => {
    if (refreshing.current) return;
    refreshing.current = true;
    const silent = opts?.silent ?? false;
    // Silent (interceptor-triggered / background) refreshes must not flip loading — that would
    // unmount RequireAuth's current page and lose in-progress form state.
    if (!silent) setLoading(true);
    try {
      // Seed the gateway XSRF-TOKEN cookie (any admin-chain GET renders it), then probe both sessions.
      await api.get(`${BASE}/health`).catch(() => {});
      const [gw, ops] = await Promise.allSettled([api.get(`${BASE}/me`), opsMe()]);
      const gwUser = gw.status === 'fulfilled' ? (gw.value.data.username ?? null) : null;
      const opsUser = ops.status === 'fulfilled' ? (ops.value.data.user ?? null) : null;
      // Distinguish "gateway reachable but session expired" (401) from "gateway unreachable"
      // (network failure) — both leave gatewayUp false, but only the former is a 401.
      setGatewayAuthExpired(gw.status === 'rejected' && is401(gw.reason));
      setGatewayUp(gw.status === 'fulfilled');
      // The api layer has no context access, so the observability modules read this flag to
      // decide whether to ask the gateway's admin routes or the ops sidecar's twins.
      setGatewayReachable(gw.status === 'fulfilled');
      setOpsUp(ops.status === 'fulfilled');
      // /me reports the flag too, so a page reload (session but no login response) still forces
      // the change form. Sent as a string because the body is a Map<String, String>.
      // The gateway answer wins when there is one; otherwise fall back to ops — in the normal
      // stop-to-edit state the gateway is DOWN, and without this the console would never learn
      // it must show the form and every ops write would just 403.
      setPasswordChangeRequired(gw.status === 'fulfilled'
              ? String(gw.value.data.passwordChangeRequired) === 'true'
              : ops.status === 'fulfilled'
                && String(ops.value.data.passwordChangeRequired) === 'true');
      setUser(gwUser ?? opsUser);
    } finally {
      refreshing.current = false;
      if (!silent) setLoading(false);
    }
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const [gw, ops] = await Promise.allSettled([
      api.post(`${BASE}/login`, { username, password }),
      opsLogin(username, password),
    ]);
    if (gw.status === 'fulfilled') {
      setPasswordChangeRequired(String(gw.value.data.passwordChangeRequired) === 'true');
    } else if (ops.status === 'fulfilled') {
      // Gateway stopped (the stop-to-edit norm): ops carries the same hint.
      setPasswordChangeRequired(String(ops.value.data.passwordChangeRequired) === 'true');
    }
    const ok = gw.status === 'fulfilled' || ops.status === 'fulfilled';
    if (!ok) {
      // A 401 from either backend is authoritative (both check the same H2 account).
      const badCreds = (gw.status === 'rejected' && is401(gw.reason))
                    || (ops.status === 'rejected' && is401(ops.reason));
      throw new Error(badCreds ? 'badcreds' : 'down');
    }
    await refresh();
  }, [refresh]);

  const attemptHandoff = useCallback(async () => {
    try {
      const { data } = await requestHandoff();
      await api.post(`${BASE}/login-token`, { token: data.token });
    } catch {
      // Jim: ops sessiya yo'q / token rad etildi — quyidagi re-probe degraded holatni saqlaydi.
    }
    await refresh({ silent: true });
  }, [refresh]);

  const logout = useCallback(async () => {
    await Promise.allSettled([api.post(`${BASE}/logout`), opsLogout()]);
    setUser(null);
    setGatewayUp(false);
    setGatewayReachable(false);
    setOpsUp(false);
    setGatewayAuthExpired(false);
    setPasswordChangeRequired(false);
  }, []);

  // Mid-session gateway 401 (expired/invalidated session): re-probe instead of hard logout,
  // so an alive ops session keeps the user in degraded mode rather than kicking to /login.
  // Silent: this fires from inside a rejected response, never the user waiting on a spinner.
  useEffect(() => { setUnauthorizedHandler(() => { refresh({ silent: true }); }); }, [refresh]);

  useEffect(() => { refresh(); }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, gatewayUp, opsUp, gatewayAuthExpired, passwordChangeRequired, login, logout, refresh, attemptHandoff }}>
      {children}
    </AuthContext.Provider>
  );
}
