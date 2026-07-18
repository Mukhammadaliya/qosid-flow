import { describe, it, expect, vi, beforeEach, afterEach, Mock } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../../i18n';
import { OpsPage } from './OpsPage';
import { AuthContext } from '../../auth/AuthContext';
import { getOpsStatus } from '../../api/ops';

vi.mock('../../api/ops', () => ({
  getOpsStatus: vi.fn().mockResolvedValue({ data: { up: true } }),
  startGateway: vi.fn().mockResolvedValue({ data: { ok: true, exitCode: 0, output: '' } }),
  stopGateway: vi.fn().mockResolvedValue({ data: { ok: true, exitCode: 0, output: '' } }),
}));

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(public url: string) { FakeEventSource.instances.push(this); }
  close() {}
  emit(line: string) { this.onmessage?.({ data: line }); }
}

beforeEach(() => { vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource); FakeEventSource.instances = []; });
afterEach(() => { vi.unstubAllGlobals(); });

function renderOpsPage(
  refresh: () => Promise<void> = vi.fn(),
  attemptHandoff: () => Promise<void> = vi.fn(),
  opsUp = true,
  logout: () => Promise<void> = vi.fn(),
) {
  return render(
    <MemoryRouter>
      <AuthContext.Provider value={{
        user: 'admin', loading: false, gatewayUp: true, opsUp, gatewayAuthExpired: false,
        login: async () => {}, logout, refresh, attemptHandoff,
      }}>
        <OpsPage />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe('OpsPage', () => {
  it('shows UP badge from status poll', async () => {
    renderOpsPage();
    await waitFor(() => expect(screen.getByText('Ishlayapti')).toBeInTheDocument());
  });

  it('renders streamed log lines', async () => {
    renderOpsPage();
    await waitFor(() => expect(FakeEventSource.instances.length).toBe(1));
    FakeEventSource.instances[0].emit('hello-log-line');
    await waitFor(() => expect(screen.getByText('hello-log-line')).toBeInTheDocument());
  });

  it('stop asks for confirmation', async () => {
    renderOpsPage();
    await waitFor(() => screen.getByText('Ishlayapti'));
    (await screen.findByRole('button', { name: /To'xtatish/ })).click();
    // antd Modal.confirm renders the title both visually and in an aria-hidden node,
    // so two matches are expected — assert at least one is present.
    await waitFor(() => expect(screen.getAllByText(/Gateway to'xtatilsinmi/).length).toBeGreaterThan(0));
  });

  it('FIX2 regression: an observed up/down transition calls refresh() once (not every poll tick)', async () => {
    vi.useFakeTimers();
    try {
      (getOpsStatus as Mock)
        .mockReset()
        .mockResolvedValueOnce({ data: { up: true } })
        .mockResolvedValueOnce({ data: { up: true } })
        .mockResolvedValueOnce({ data: { up: false } })
        .mockResolvedValue({ data: { up: false } });
      const refresh = vi.fn().mockResolvedValue(undefined);

      renderOpsPage(refresh);

      // Initial tick fires immediately on mount: up:true, no prior value -> no refresh.
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      expect(getOpsStatus).toHaveBeenCalledTimes(1);
      expect(refresh).not.toHaveBeenCalled();

      // Second tick (5s later): up:true again — same value, no transition, no refresh.
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(getOpsStatus).toHaveBeenCalledTimes(2);
      expect(refresh).not.toHaveBeenCalled();

      // Third tick: up flips to false — exactly one transition, exactly one silent refresh.
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(getOpsStatus).toHaveBeenCalledTimes(3);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(refresh).toHaveBeenCalledWith({ silent: true });
    } finally {
      vi.useRealTimers();
    }
  });

  it('Task 9: a down→up transition triggers attemptHandoff (avto-ulanish), not a bare refresh', async () => {
    vi.useFakeTimers();
    try {
      (getOpsStatus as Mock)
        .mockReset()
        .mockResolvedValueOnce({ data: { up: false } })
        .mockResolvedValue({ data: { up: true } });
      const refresh = vi.fn().mockResolvedValue(undefined);
      const attemptHandoff = vi.fn().mockResolvedValue(undefined);

      renderOpsPage(refresh, attemptHandoff);

      // Mount tick: down, no prior value -> nothing fires.
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      expect(attemptHandoff).not.toHaveBeenCalled();

      // Next tick: down→up — auto-reconnect via handoff (which does its own silent refresh).
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(attemptHandoff).toHaveBeenCalledTimes(1);
      expect(refresh).not.toHaveBeenCalled();

      // Further up ticks: no new transition, no repeat handoff.
      await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
      expect(attemptHandoff).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('guard: opsUp=false renders the unavailable alert, never polls status, never opens SSE', async () => {
    (getOpsStatus as Mock).mockClear();
    renderOpsPage(vi.fn(), vi.fn(), false);
    expect(screen.getByText("Ops bilan aloqa yo'q yoki sessiya tugagan")).toBeInTheDocument();
    expect(getOpsStatus).not.toHaveBeenCalled();
    expect(FakeEventSource.instances.length).toBe(0);
  });

  it('guard: the re-login button calls logout()', async () => {
    const logout = vi.fn().mockResolvedValue(undefined);
    renderOpsPage(vi.fn(), vi.fn(), false, logout);
    (await screen.findByRole('button', { name: 'Kirish' })).click();
    await waitFor(() => expect(logout).toHaveBeenCalled());
  });
});
