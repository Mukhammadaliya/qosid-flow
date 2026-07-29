import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { DockerPage } from './DockerPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('DockerPage', () => {
  it('loads container names and renders docker log lines', async () => {
    vi.spyOn(logsApi, 'getLabelValues').mockResolvedValue(['redis', 'redpanda']);
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'connection refused', labels: { container: 'redis' } }],
    });
    render(<DockerPage />);
    await waitFor(() => expect(screen.getByText('connection refused')).toBeInTheDocument());
  });
});
