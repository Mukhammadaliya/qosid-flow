import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { StatisticsPage } from './StatisticsPage';
import * as statsApi from '../../api/stats';

vi.mock('@ant-design/plots', () => ({
  Line: () => <div data-testid="chart-line" />,
  Column: () => <div data-testid="chart-column" />,
  Funnel: () => <div data-testid="chart-funnel" />,
}));

beforeEach(() => vi.restoreAllMocks());

describe('StatisticsPage charts', () => {
  it('renders funnel, errors, and latency charts once their presets load', async () => {
    vi.spyOn(statsApi, 'queryStats').mockImplementation((preset: string) => {
      if (preset === 'funnel') return Promise.resolve({ series: [], kpis: { pull: 0, produce: 100, consume: 98, http: 95, save: 90 } });
      if (preset === 'errors') return Promise.resolve({ series: [{ name: '4xx', points: [{ t: 1, v: 2 }] }], kpis: {} });
      if (preset === 'latency') return Promise.resolve({ series: [{ name: 'e2eP95', points: [{ t: 1, v: 250 }] }], kpis: {} });
      return Promise.resolve({ series: [], kpis: {} });
    });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByTestId('chart-funnel')).toBeInTheDocument());
    expect(screen.getByTestId('chart-column')).toBeInTheDocument();
    // two Line charts now (throughput + latency)
    expect(screen.getAllByTestId('chart-line').length).toBeGreaterThanOrEqual(2);
  });
});
