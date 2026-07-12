import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { AdvancedSettingsPage } from './AdvancedSettingsPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const rows = [
  { key: 'gateway.response.threshold-size', value: '1024', secret: false, updatedAt: '', updatedBy: 'admin' },
  { key: 'gateway.source.auth.basic.password', value: '***', secret: true, updatedAt: '', updatedBy: 'admin' },
];

describe('AdvancedSettingsPage', () => {
  it('lists settings keys and values', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([]);
    render(<AdvancedSettingsPage />);
    await waitFor(() => expect(screen.getByText('gateway.response.threshold-size')).toBeInTheDocument());
    expect(screen.getByText('1024')).toBeInTheDocument();
  });

  it('saves an edited value via putSetting preserving the secret flag', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([]);
    const put = vi.spyOn(settingsApi, 'putSetting').mockResolvedValue({} as any);
    render(<AdvancedSettingsPage />);
    await waitFor(() => expect(screen.getByText('gateway.response.threshold-size')).toBeInTheDocument());
    await userEvent.click(screen.getAllByRole('button', { name: 'Saqlash' })[0]);
    // Edit modal opens with the row; change value then confirm.
    const input = await screen.findByLabelText('Qiymat');
    await userEvent.clear(input);
    await userEvent.type(input, '2048');
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.response.threshold-size', '2048', false));
  });

  it('does not wipe a stored secret when the edit is left blank', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([]);
    const put = vi.spyOn(settingsApi, 'putSetting').mockResolvedValue({} as any);
    render(<AdvancedSettingsPage />);
    await waitFor(() => expect(screen.getByText('gateway.source.auth.basic.password')).toBeInTheDocument());
    // Open the SECRET row's editor (second "Saqlash" button), leave blank, confirm.
    await userEvent.click(screen.getAllByRole('button', { name: 'Saqlash' })[1]);
    const input = await screen.findByLabelText('Qiymat');
    expect(input).toHaveValue(''); // secret editor starts blank by design
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    // Blank-means-keep: putSetting must NOT fire, so the stored secret is preserved.
    expect(put).not.toHaveBeenCalled();
  });
});
