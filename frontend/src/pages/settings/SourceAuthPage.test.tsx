import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { SourceAuthPage } from './SourceAuthPage';
import * as settingsApi from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const baseGet = {
  type: 'basic', basicUsername: 'svc', basicPassword: '***',
  apikeyHeader: 'X-Api-Key', apikeyValue: '', oauth2TokenUrl: '', oauth2ClientId: '',
  oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '', oauth2ClientType: 'standard',
};

const opsRows = [
  { key: 'gateway.source.auth.type', value: 'basic', secret: false, group: 'Boshqa', label: 'gateway.source.auth.type', type: 'text', editable: true },
  { key: 'gateway.source.auth.basic.username', value: 'svc', secret: false, group: 'Boshqa', label: 'gateway.source.auth.basic.username', type: 'text', editable: true },
  { key: 'gateway.source.auth.basic.password', value: '***', secret: true, group: 'Boshqa', label: 'gateway.source.auth.basic.password', type: 'text', editable: true },
];

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <SourceAuthPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('SourceAuthPage — service UP (read-only mode)', () => {
  it('loads from the gateway, disables inputs, and offers no save button', async () => {
    const get = vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('svc')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Saqlash' })).toBeNull();
  });

  it('shows the stop-to-edit banner', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.stopToEdit'));
  });

  it('keeps the Test button working and renders the backend result', async () => {
    vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    vi.spyOn(settingsApi, 'testSourceAuth').mockResolvedValue({ ok: true, type: 'basic', message: 'Auth configured' } as any);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    const testBtn = screen.getByRole('button', { name: 'Sinash' });
    expect(testBtn).toBeEnabled();
    await userEvent.click(testBtn);
    await waitFor(() => expect(screen.getByText('Auth configured')).toBeInTheDocument());
  });
});

describe('SourceAuthPage — service DOWN + ops session (edit mode)', () => {
  it('loads current values from the ops API instead of the gateway', async () => {
    const get = vi.spyOn(settingsApi, 'getSourceAuth').mockResolvedValue({ ...baseGet } as any);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    expect(opsGet).toHaveBeenCalled();
    expect(get).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('svc')).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.appliesOnStart'));
  });

  it('shows apikey fields when type switches to apikey', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByTitle('apikey'));
    expect(screen.getByLabelText('Header nomi')).toBeInTheDocument();
  });

  it('renders the oauth2 client-type select when type switches to oauth2', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByTitle('oauth2'));
    expect(screen.getByText("Token so'rovi formati")).toBeInTheDocument();
    expect(screen.getByLabelText('Token URL')).toBeInTheDocument();
  });

  it('saves via PUT /ops/api/settings/* and omits the untouched blank password', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    const username = screen.getByDisplayValue('svc');
    await userEvent.clear(username);
    await userEvent.type(username, 'svc2');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.source.auth.basic.username', 'svc2'));
    expect(put).toHaveBeenCalledWith('gateway.source.auth.type', 'basic');
    expect(put).toHaveBeenCalledTimes(2); // password left blank -> keep existing, no PUT
  });

  it('sends the password too when a new one is typed', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText('Parol'), 'newpw');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.source.auth.basic.password', 'newpw'));
    expect(put).toHaveBeenCalledTimes(3);
  });

  it('disables the Test button (gateway-side call needs a running service)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('svc')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Sinash' })).toBeDisabled();
  });
});
