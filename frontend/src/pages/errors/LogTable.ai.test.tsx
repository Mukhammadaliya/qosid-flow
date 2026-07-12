import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { LogTable } from './LogTable';
import type { LogEntry } from '../../api/logs';
import * as aiApi from '../../api/ai';

beforeEach(() => vi.restoreAllMocks());

const entry: LogEntry = { timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'boom', labels: { stream: 'dlq' }, parsed: { failureReason: 'timeout' } };
const columns = [{ title: 'msg', dataIndex: 'line' }];

describe('LogTable AI', () => {
  it('shows the AI button and renders analysis when enabled', async () => {
    vi.spyOn(aiApi, 'getAiStatus').mockResolvedValue({ enabled: true });
    vi.spyOn(aiApi, 'analyzeError').mockResolvedValue({ analysis: 'Root cause: upstream timeout.', error: null });
    render(<LogTable entries={[entry]} loading={false} columns={columns as any} />);
    await userEvent.click(screen.getByText('boom'));                 // open drawer
    const btn = await screen.findByRole('button', { name: 'AI tahlil' });
    await userEvent.click(btn);
    await waitFor(() => expect(screen.getByText(/Root cause: upstream timeout/)).toBeInTheDocument());
  });

  it('hides the AI button when disabled', async () => {
    vi.spyOn(aiApi, 'getAiStatus').mockResolvedValue({ enabled: false });
    render(<LogTable entries={[entry]} loading={false} columns={columns as any} />);
    await userEvent.click(screen.getByText('boom'));
    await waitFor(() => expect(screen.getByText(/timeout/)).toBeInTheDocument()); // drawer open (parsed shown)
    expect(screen.queryByRole('button', { name: 'AI tahlil' })).toBeNull();
  });
});
