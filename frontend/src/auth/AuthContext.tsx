import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import axios from 'axios';
import { api, setUnauthorizedHandler } from '../api/client';
import { opsLogin, opsLogout, opsMe } from '../api/ops';

interface AuthState {
  user: string | null;
  loading: boolean;
  gatewayUp: boolean;
  opsUp: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
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

  const refresh = useCallback(async () => {
    setLoading(true);
    // Seed the gateway XSRF-TOKEN cookie (any admin-chain GET renders it), then probe both sessions.
    await api.get(`${BASE}/health`).catch(() => {});
    const [gw, ops] = await Promise.allSettled([api.get(`${BASE}/me`), opsMe()]);
    const gwUser = gw.status === 'fulfilled' ? (gw.value.data.username ?? null) : null;
    const opsUser = ops.status === 'fulfilled' ? (ops.value.data.user ?? null) : null;
    setGatewayUp(gw.status === 'fulfilled');
    setOpsUp(ops.status === 'fulfilled');
    setUser(gwUser ?? opsUser);
    setLoading(false);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const [gw, ops] = await Promise.allSettled([
      api.post(`${BASE}/login`, { username, password }),
      opsLogin(username, password),
    ]);
    const ok = gw.status === 'fulfilled' || ops.status === 'fulfilled';
    if (!ok) {
      // A 401 from either backend is authoritative (both check the same H2 account).
      const badCreds = (gw.status === 'rejected' && is401(gw.reason))
                    || (ops.status === 'rejected' && is401(ops.reason));
      throw new Error(badCreds ? 'badcreds' : 'down');
    }
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    await Promise.allSettled([api.post(`${BASE}/logout`), opsLogout()]);
    setUser(null);
    setGatewayUp(false);
    setOpsUp(false);
  }, []);

  // Mid-session gateway 401 (expired/invalidated session): re-probe instead of hard logout,
  // so an alive ops session keeps the user in degraded mode rather than kicking to /login.
  useEffect(() => { setUnauthorizedHandler(() => { refresh(); }); }, [refresh]);

  useEffect(() => { refresh(); }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, gatewayUp, opsUp, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}
