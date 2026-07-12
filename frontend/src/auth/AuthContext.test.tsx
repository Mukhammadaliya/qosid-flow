import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { api } from '../api/client';

function Probe() {
  const { user, loading } = useAuth();
  return <div>{loading ? 'loading' : `user:${user ?? 'none'}`}</div>;
}

describe('AuthProvider', () => {
  beforeEach(() => vi.restoreAllMocks());

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
});
