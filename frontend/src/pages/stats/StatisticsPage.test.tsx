import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('offers the long ranges and scales the step so Prometheus is not overrun', async () => {
    const spy = vi.spyOn(statsApi, 'queryStats').mockResolvedValue({ series: [], kpis: {} });
    render(<StatisticsPage />);
    await waitFor(() => expect(spy).toHaveBeenCalled());

    // Default 1h: ~300 points, floored at a 15s step.
    expect(spy.mock.calls[0][1]).toMatchObject({ step: 15 });

    await userEvent.click(screen.getByRole('combobox'));
    for (const label of ['15m', '1h', '6h', '24h', '7d', '30d']) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }

    spy.mockClear();
    await userEvent.click(screen.getByTitle('7d'));
    await waitFor(() => expect(spy).toHaveBeenCalled());
    // 7d at the old fixed 30s step would be 20160 points — past Prometheus's 11000 ceiling.
    const step = (spy.mock.calls[0][1] as { step: number }).step;
    expect(step).toBe(2016);
    expect(604_800 / step).toBeLessThan(11_000);
  });

  it('shows the Prometheus error banner', async () => {
    vi.spyOn(statsApi, 'queryStats').mockResolvedValue({ series: [], kpis: {}, error: 'Prometheus unreachable' });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByText(/Prometheus unreachable/)).toBeInTheDocument());
  });
});
