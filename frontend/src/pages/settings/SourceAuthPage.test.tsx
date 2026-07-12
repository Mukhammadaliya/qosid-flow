import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { SourceAuthPage } from './SourceAuthPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const baseGet = {
  type: 'basic', basicUsername: 'svc', basicPassword: '***',
  apikeyHeader: 'X-Api-Key', apikeyValue: '', oauth2TokenUrl: '', oauth2ClientId: '',
  oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '',
};

describe('SourceAuthPage', () => {
  it('shows apikey fields when type switches to apikey', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    render(<SourceAuthPage />);
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByTitle('apikey'));
    expect(screen.getByLabelText('Header nomi')).toBeInTheDocument();
  });

  it('renders the test result returned by the backend', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    vi.spyOn(settingsApi, 'testSourceAuth').mockResolvedValue({ ok: true, type: 'basic', message: 'Auth configured' } as any);
    render(<SourceAuthPage />);
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Sinash' }));
    await waitFor(() => expect(screen.getByText('Auth configured')).toBeInTheDocument());
  });
});
