import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { OpsPage } from './OpsPage';

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

describe('OpsPage', () => {
  it('shows UP badge from status poll', async () => {
    render(<OpsPage />);
    await waitFor(() => expect(screen.getByText('Ishlayapti')).toBeInTheDocument());
  });

  it('renders streamed log lines', async () => {
    render(<OpsPage />);
    await waitFor(() => expect(FakeEventSource.instances.length).toBe(1));
    FakeEventSource.instances[0].emit('hello-log-line');
    await waitFor(() => expect(screen.getByText('hello-log-line')).toBeInTheDocument());
  });

  it('stop asks for confirmation', async () => {
    render(<OpsPage />);
    await waitFor(() => screen.getByText('Ishlayapti'));
    (await screen.findByRole('button', { name: /To'xtatish/ })).click();
    // antd Modal.confirm renders the title both visually and in an aria-hidden node,
    // so two matches are expected — assert at least one is present.
    await waitFor(() => expect(screen.getAllByText(/Gateway to'xtatilsinmi/).length).toBeGreaterThan(0));
  });
});
