import { describe, it, expect, vi, beforeEach, Mock } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { api } from '../api/client';
import { opsLogin, opsMe, requestHandoff } from '../api/ops';
import { isGatewayReachable } from '../api/gatewayReach';

vi.mock('../api/ops', () => ({
  opsLogin: vi.fn(),
  opsLogout: vi.fn().mockResolvedValue(undefined),
  opsMe: vi.fn(),
  requestHandoff: vi.fn(),
}));

const BASE = '/qosid/admin/api';
const axios401 = { isAxiosError: true, response: { status: 401 } };
const axiosDown = { isAxiosError: true, response: undefined, code: 'ERR_NETWORK' };

function Probe() {
  const { user, loading, gatewayUp, opsUp } = useAuth();
  return (
    <div>
      <div>{loading ? 'loading' : `user:${user ?? 'none'}`}</div>
      <div>gatewayUp:{String(gatewayUp)}</div>
      <div>opsUp:{String(opsUp)}</div>
    </div>
  );
}

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    // Default: ops backend unreachable, so pre-existing single-backend tests keep
    // their old semantics (gateway is the sole source of truth for them).
    (opsMe as Mock).mockRejectedValue(axiosDown);
    (opsLogin as Mock).mockRejectedValue(axiosDown);
  });

  it('resolves the current user from /me after bootstrap', async () => {
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.resolve({ data: { username: 'admin' } } as any));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:admin')).toBeInTheDocument());
  });

  it('reports no user when /me returns 401', async () => {
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.reject({ response: { status: 401 } }));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:none')).toBeInTheDocument());
  });

  it('clears the user when the api client reports a mid-session 401', async () => {
    // Session starts valid, then goes invalid at the same moment the interceptor
    // fires (mirroring a real expired-session 401): refresh() re-probes and this
    // time /me itself rejects, so the user is dropped for real rather than by
    // fiat.
    let sessionValid = true;
    vi.spyOn(api, 'get').mockImplementation((url: string) => {
      if (url.endsWith('/health')) return Promise.resolve({ data: {} } as any);
      return sessionValid
        ? Promise.resolve({ data: { username: 'admin' } } as any)
        : Promise.reject({ response: { status: 401 } });
    });
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:admin')).toBeInTheDocument());

    // Drive the real axios response interceptor with a 401 — it must invoke the
    // handler AuthProvider registered via setUnauthorizedHandler and re-probe.
    sessionValid = false;
    const rejected = (api.interceptors.response as any).handlers[0].rejected;
    await act(async () => {
      await rejected({ response: { status: 401 } }).catch(() => {});
    });
    await waitFor(() => expect(screen.getByText('user:none')).toBeInTheDocument());
  });

  it('mid-session gateway 401 with an alive ops session -> degraded mode, not a hard logout', async () => {
    // Gateway session dies mid-session (mirrors the test above), but this time the
    // ops session is alive throughout. The interceptor must re-probe rather than
    // hard-clear the user: gatewayUp flips false, but user stays set from ops.
    let gatewaySessionValid = true;
    vi.spyOn(api, 'get').mockImplementation((url: string) => {
      if (url.endsWith('/health')) return Promise.resolve({ data: {} } as any);
      return gatewaySessionValid
        ? Promise.resolve({ data: { username: 'admin' } } as any)
        : Promise.reject({ response: { status: 401 } });
    });
    (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:admin')).toBeInTheDocument());

    gatewaySessionValid = false;
    const rejected = (api.interceptors.response as any).handlers[0].rejected;
    await act(async () => {
      await rejected({ response: { status: 401 } }).catch(() => {});
    });

    // If the unauthorized handler were reverted to a hard `setUser(null)`, this
    // would regress to 'user:none' even though ops is still authenticated.
    await waitFor(() => expect(screen.getByText('gatewayUp:false')).toBeInTheDocument());
    expect(screen.getByText('user:admin')).toBeInTheDocument();
    expect(screen.getByText('opsUp:true')).toBeInTheDocument();
  });

  it('full mode: both logins succeed -> user set, gatewayUp && opsUp', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: {} } as any);
    vi.spyOn(api, 'get').mockResolvedValue({ data: { username: 'admin' } } as any);
    (opsLogin as Mock).mockResolvedValue({ data: {} });
    (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });

    let auth: ReturnType<typeof useAuth> | undefined;
    function Capture() {
      auth = useAuth();
      return null;
    }
    render(<AuthProvider><Capture /></AuthProvider>);
    await waitFor(() => expect(auth?.loading).toBe(false));

    await act(async () => {
      await auth!.login('admin', 'admin');
    });

    expect(auth!.user).toBe('admin');
    expect(auth!.gatewayUp).toBe(true);
    expect(auth!.opsUp).toBe(true);
  });

  it('degraded: gateway down, ops ok -> login resolves, user set, gatewayUp=false', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(axiosDown);
    vi.spyOn(api, 'get').mockRejectedValue(axiosDown);
    (opsLogin as Mock).mockResolvedValue({ data: {} });
    (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });

    let auth: ReturnType<typeof useAuth> | undefined;
    function Capture() {
      auth = useAuth();
      return null;
    }
    render(<AuthProvider><Capture /></AuthProvider>);
    await waitFor(() => expect(auth?.loading).toBe(false));

    await act(async () => {
      await auth!.login('admin', 'admin');
    });

    expect(auth!.user).toBe('admin');
    expect(auth!.gatewayUp).toBe(false);
    expect(auth!.opsUp).toBe(true);
  });

  /**
   * The forced-password-change hint used to come from the gateway alone. In the normal
   * stop-to-edit state the gateway is DOWN — which is exactly when an operator on a fresh
   * install still has admin/admin — so the console never learned it had to show the change
   * form and every ops write came back 403 with no explanation.
   */
  describe('passwordChangeRequired source', () => {
    function capture() {
      let auth: ReturnType<typeof useAuth> | undefined;
      function Capture() {
        auth = useAuth();
        return null;
      }
      render(<AuthProvider><Capture /></AuthProvider>);
      return () => auth!;
    }

    it('takes the ops hint when the gateway is unreachable', async () => {
      vi.spyOn(api, 'get').mockRejectedValue(axiosDown);
      (opsMe as Mock).mockResolvedValue({ data: { user: 'admin', passwordChangeRequired: 'true' } });

      const auth = capture();
      await waitFor(() => expect(auth().loading).toBe(false));

      expect(auth().gatewayUp).toBe(false);
      expect(auth().passwordChangeRequired).toBe(true);
    });

    it('lets the gateway answer win over ops when both replied', async () => {
      vi.spyOn(api, 'get').mockImplementation((url: string) =>
        url.endsWith('/health')
          ? Promise.resolve({ data: {} } as any)
          : Promise.resolve({ data: { username: 'admin', passwordChangeRequired: 'false' } } as any));
      (opsMe as Mock).mockResolvedValue({ data: { user: 'admin', passwordChangeRequired: 'true' } });

      const auth = capture();
      await waitFor(() => expect(auth().loading).toBe(false));

      // The gateway is the authority for its own routes; a stale ops answer must not
      // strand the operator on the change form.
      expect(auth().passwordChangeRequired).toBe(false);
    });

    it('takes the ops hint from the login response when only ops accepted the login', async () => {
      vi.spyOn(api, 'post').mockRejectedValue(axiosDown);
      vi.spyOn(api, 'get').mockRejectedValue(axiosDown);
      (opsLogin as Mock).mockResolvedValue({ data: { passwordChangeRequired: 'true' } });
      (opsMe as Mock).mockResolvedValue({ data: { user: 'admin', passwordChangeRequired: 'true' } });

      const auth = capture();
      await waitFor(() => expect(auth().loading).toBe(false));
      await act(async () => {
        await auth().login('admin', 'admin');
      });

      expect(auth().passwordChangeRequired).toBe(true);
    });
  });

  it('bad credentials: any 401 -> login throws badcreds', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(axiosDown);
    vi.spyOn(api, 'post').mockRejectedValue(axios401);
    (opsLogin as Mock).mockRejectedValue(axios401);

    let auth: ReturnType<typeof useAuth> | undefined;
    function Capture() {
      auth = useAuth();
      return null;
    }
    render(<AuthProvider><Capture /></AuthProvider>);
    await waitFor(() => expect(auth?.loading).toBe(false));

    await expect(auth!.login('admin', 'admin')).rejects.toThrow('badcreds');
  });

  it('FIX1 regression: bounded 401 refresh loop — concurrent 401s cause at most one extra /me probe', async () => {
    // Pre-fix, an unauthenticated visitor (or a gateway restart) triggers an unbounded loop:
    // /me 401s -> interceptor -> onUnauthorized() -> refresh() -> its own /me probe 401s ->
    // interceptor -> onUnauthorized() -> refresh() -> ... forever. Two independent guards now
    // break this: (1) client.ts never calls onUnauthorized for the probe's own '/me' request,
    // and (2) AuthContext's refreshing-ref re-entrancy guard collapses concurrent refresh()
    // calls triggered by other 401s into a single in-flight probe.
    let meCalls = 0;
    vi.spyOn(api, 'get').mockImplementation((url: string) => {
      if (url.endsWith('/health')) return Promise.resolve({ data: {} } as any);
      meCalls++;
      return Promise.reject({ config: { url }, response: { status: 401 } });
    });

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('user:none')).toBeInTheDocument());
    expect(meCalls).toBe(1); // the mount-time refresh's own /me probe

    // Drive the REAL registered response interceptor directly (same technique as the mid-session
    // tests above), simulating a 401 storm: two unrelated admin-API calls 401 concurrently (each
    // would, pre-fix, schedule its own refresh()), plus the probe's own '/me' 401 (must never
    // re-trigger onUnauthorized at all). None of these are awaited between each other, so any
    // recursive/concurrent refresh() calls they schedule are still in flight together.
    const rejected = (api.interceptors.response as any).handlers[0].rejected;
    await act(async () => {
      await Promise.allSettled([
        rejected({ config: { url: `${BASE}/settings` }, response: { status: 401 } }),
        rejected({ config: { url: `${BASE}/settings` }, response: { status: 401 } }),
        rejected({ config: { url: `${BASE}/me` }, response: { status: 401 } }),
      ]);
    });

    expect(meCalls).toBeLessThanOrEqual(2);
  });

  it('both down -> login throws down', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(axiosDown);
    vi.spyOn(api, 'post').mockRejectedValue(axiosDown);
    (opsLogin as Mock).mockRejectedValue(axiosDown);

    let auth: ReturnType<typeof useAuth> | undefined;
    function Capture() {
      auth = useAuth();
      return null;
    }
    render(<AuthProvider><Capture /></AuthProvider>);
    await waitFor(() => expect(auth?.loading).toBe(false));

    await expect(auth!.login('admin', 'admin')).rejects.toThrow('down');
  });

  // Task 9 (D-loyiha): avto-ulanish — ops handoff token -> gateway login-token -> silent re-probe.
  describe('attemptHandoff', () => {
    it('handoff + login-token succeed -> session established, gatewayUp true after re-probe', async () => {
      // Gateway starts session-less (down-from-auth-perspective); the login-token call
      // "creates" the session, after which the /me re-probe succeeds.
      let gatewaySession = false;
      vi.spyOn(api, 'get').mockImplementation((url: string) => {
        if (url.endsWith('/health')) return Promise.resolve({ data: {} } as never);
        return gatewaySession
          ? Promise.resolve({ data: { username: 'admin' } } as never)
          : Promise.reject(axios401);
      });
      const post = vi.spyOn(api, 'post').mockImplementation((url: string) => {
        if (url.endsWith('/login-token')) {
          gatewaySession = true;
          return Promise.resolve({ data: { username: 'admin', status: 'AUTHENTICATED' } } as never);
        }
        return Promise.resolve({ data: {} } as never);
      });
      (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });
      (requestHandoff as Mock).mockResolvedValue({ data: { token: 't-one-time' } });

      let auth: ReturnType<typeof useAuth> | undefined;
      function Capture() {
        auth = useAuth();
        return null;
      }
      render(<AuthProvider><Capture /></AuthProvider>);
      await waitFor(() => expect(auth?.loading).toBe(false));
      expect(auth!.gatewayUp).toBe(false);

      await act(async () => { await auth!.attemptHandoff(); });

      expect(post).toHaveBeenCalledWith(`${BASE}/login-token`, { token: 't-one-time' });
      expect(auth!.gatewayUp).toBe(true);
      expect(auth!.user).toBe('admin');
    });

    it('handoff rejected -> login-token never called, error swallowed, still re-probes', async () => {
      let meCalls = 0;
      vi.spyOn(api, 'get').mockImplementation((url: string) => {
        if (url.endsWith('/health')) return Promise.resolve({ data: {} } as never);
        meCalls++;
        return Promise.reject(axiosDown);
      });
      const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} } as never);
      (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });
      (requestHandoff as Mock).mockRejectedValue(axios401);

      let auth: ReturnType<typeof useAuth> | undefined;
      function Capture() {
        auth = useAuth();
        return null;
      }
      render(<AuthProvider><Capture /></AuthProvider>);
      await waitFor(() => expect(auth?.loading).toBe(false));
      const callsBefore = meCalls;

      // Must resolve (no throw) even though the handoff itself 401'd.
      await act(async () => { await auth!.attemptHandoff(); });

      expect(post).not.toHaveBeenCalled();
      expect(meCalls).toBeGreaterThan(callsBefore); // re-probe still happened
      expect(auth!.user).toBe('admin'); // ops session keeps the user in degraded mode
    });
  });

  it('publishes gateway reachability to the api layer so log/stat reads pick a live backend', async () => {
    // The api modules cannot read React context; they consult api/gatewayReach. If refresh()
    // never writes it, the ops fallback is unreachable code and the Errors/Statistics pages
    // keep hammering a dead gateway.
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.resolve({ data: { username: 'admin' } } as any));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('gatewayUp:true')).toBeInTheDocument());
    expect(isGatewayReachable()).toBe(true);
  });

  it('marks the gateway unreachable when its probe fails, with ops still alive', async () => {
    (opsMe as Mock).mockResolvedValue({ data: { user: 'admin' } });
    vi.spyOn(api, 'get').mockImplementation((url: string) =>
      url.endsWith('/health')
        ? Promise.resolve({ data: {} } as any)
        : Promise.reject(axiosDown));
    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('gatewayUp:false')).toBeInTheDocument());
    expect(isGatewayReachable()).toBe(false);
  });
});
