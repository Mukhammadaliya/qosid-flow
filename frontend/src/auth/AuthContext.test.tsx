import { describe, it, expect, vi, beforeEach, Mock } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { api } from '../api/client';
import { opsLogin, opsMe } from '../api/ops';

vi.mock('../api/ops', () => ({
  opsLogin: vi.fn(),
  opsLogout: vi.fn().mockResolvedValue(undefined),
  opsMe: vi.fn(),
}));

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
});
