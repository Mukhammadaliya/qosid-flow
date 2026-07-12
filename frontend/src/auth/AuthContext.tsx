import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import { api, setUnauthorizedHandler } from '../api/client';

interface AuthState {
  user: string | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

export const AuthContext = createContext<AuthState>(null as unknown as AuthState);
export const useAuth = () => useContext(AuthContext);

const BASE = '/qosid/admin/api';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      // Seed the XSRF-TOKEN cookie (any admin-chain GET renders it), then check session.
      await api.get(`${BASE}/health`).catch(() => {});
      const res = await api.get(`${BASE}/me`);
      setUser(res.data.username ?? null);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    await api.post(`${BASE}/login`, { username, password });
    await refresh();
  }, [refresh]);

  const logout = useCallback(async () => {
    await api.post(`${BASE}/logout`).catch(() => {});
    setUser(null);
  }, []);

  // Mid-session 401 (expired/invalidated session) clears the user so RequireAuth
  // redirects to /login. Harmless on login-failure 401s — the user is already null.
  useEffect(() => { setUnauthorizedHandler(() => setUser(null)); }, []);

  useEffect(() => { refresh(); }, [refresh]);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}
