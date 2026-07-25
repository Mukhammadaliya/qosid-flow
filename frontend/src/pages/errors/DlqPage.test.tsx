import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { DlqPage } from './DlqPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('DlqPage', () => {
  it('renders parsed DLQ fields from Loki entries', async () => {
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{
        timestamp: '2026-07-12T10:00:00Z', level: 'ERROR',
        line: '{"failureReason":"timeout","tenantId":100,"httpStatus":504}',
        labels: { stream: 'dlq' },
        parsed: { failureReason: 'timeout', tenantId: 100, httpStatus: 504 },
      }],
    });
    render(<DlqPage />);
    await waitFor(() => expect(screen.getByText('timeout')).toBeInTheDocument());
    expect(screen.getByText('504')).toBeInTheDocument();
  });

  it('shows the Loki-unreachable error banner', async () => {
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({ entries: [], error: 'Loki unreachable' });
    render(<DlqPage />);
    await waitFor(() => expect(screen.getByText(/Loki unreachable/)).toBeInTheDocument());
  });
});
