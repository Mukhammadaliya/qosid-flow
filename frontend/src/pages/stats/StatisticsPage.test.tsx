import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { StatisticsPage } from './StatisticsPage';
import * as statsApi from '../../api/stats';

// @ant-design/plots renders to canvas/DOM that jsdom can't fully draw — stub the charts.
vi.mock('@ant-design/plots', () => ({
  Line: () => <div data-testid="chart-line" />,
  Column: () => <div data-testid="chart-column" />,
  Funnel: () => <div data-testid="chart-funnel" />,
}));

beforeEach(() => vi.restoreAllMocks());

describe('StatisticsPage', () => {
  it('renders KPI values from the kpi preset', async () => {
    vi.spyOn(statsApi, 'queryStats').mockImplementation((preset: string) => {
      if (preset === 'kpi') return Promise.resolve({ series: [], kpis: { throughput: 7, successRate: 99, errorRate: 1, dlq: 3, e2eP95: 250 } });
      return Promise.resolve({ series: [], kpis: {} });
    });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByText('7')).toBeInTheDocument()); // throughput KPI
    expect(screen.getByText('3')).toBeInTheDocument(); // dlq KPI
  });

  it('shows the Prometheus error banner', async () => {
    vi.spyOn(statsApi, 'queryStats').mockResolvedValue({ series: [], kpis: {}, error: 'Prometheus unreachable' });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByText(/Prometheus unreachable/)).toBeInTheDocument());
  });
});
