import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { GeneralSettingsPage } from './GeneralSettingsPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const registry = {
  restartPending: false,
  settings: [
    { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', effectClass: 'LIVE', value: 'https://a.example.com', secret: false },
    { key: 'gateway.retry.max-attempts', group: 'Retry/HTTP', label: 'Max attempts', type: 'number', effectClass: 'REQUIRES_RESTART', value: '3', secret: false },
    { key: 'server.port', group: 'Infratuzilma', label: 'App port', type: 'number', effectClass: 'READ_ONLY', value: '8090', secret: false },
  ],
};

describe('GeneralSettingsPage', () => {
  it('renders grouped settings with effect-class badges', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.getByDisplayValue('https://a.example.com')).toBeInTheDocument();
    // read-only field is disabled
    expect(screen.getByDisplayValue('8090')).toBeDisabled();
  });

  it('saves a live setting via putSetting', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    const put = vi.spyOn(settingsApi, 'putSetting').mockResolvedValue({} as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    const input = screen.getByDisplayValue('https://a.example.com');
    await userEvent.clear(input);
    await userEvent.type(input, 'https://b.example.com');
    await userEvent.click(screen.getAllByRole('button', { name: /Saqlash/ })[0]);
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.api.base-url', 'https://b.example.com', false));
  });

  it('shows the restart-pending banner when flag is set', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue({ ...registry, restartPending: true } as any);
    render(<GeneralSettingsPage />);
    // The banner is the only role="alert" on the page; the legend tag also mentions
    // "restart", so a bare getByText(/restart/i) would multi-match. Query by role instead.
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(/restart/i);
  });

  it('does not show the restart-pending banner when flag is clear', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('does not allow editing a read-only field save', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('App port')).toBeInTheDocument());
    // read-only row renders a lock icon (aria-label "lock") and no Saqlash button
    const portRow = screen.getByText('App port').closest('div')!;
    expect(within(portRow as HTMLElement).getByRole('img', { name: 'lock' })).toBeInTheDocument();
    expect(within(portRow as HTMLElement).queryByRole('button')).toBeNull();
  });
});
