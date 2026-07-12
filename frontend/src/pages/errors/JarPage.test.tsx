import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { JarPage } from './JarPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('JarPage', () => {
  it('queries source=jar with default level ERROR and renders lines', async () => {
    const spy = vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'NPE at Foo', labels: { job: 'qosid-jar' } }],
    });
    render(<JarPage />);
    await waitFor(() => expect(screen.getByText('NPE at Foo')).toBeInTheDocument());
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ source: 'jar', level: 'ERROR' }));
  });
});
