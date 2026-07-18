import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { GeneralSettingsPage } from './GeneralSettingsPage';
import * as settingsApi from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const registry = {
  settings: [
    { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', effectClass: 'LIVE', value: 'https://a.example.com', secret: false },
    { key: 'gateway.retry.max-attempts', group: 'Retry/HTTP', label: 'Max attempts', type: 'number', effectClass: 'REQUIRES_RESTART', value: '3', secret: false },
    { key: 'server.port', group: 'Infratuzilma', label: 'App port', type: 'number', effectClass: 'READ_ONLY', value: '8090', secret: false },
  ],
};

const opsRows = [
  { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', value: 'https://a.example.com', secret: false, editable: true },
  { key: 'server.port', group: 'Infratuzilma', label: 'App port', type: 'number', value: '8090', secret: false, editable: false },
  { key: 'gateway.settings.enc-key', group: 'Xavfsizlik', label: 'Shifrlash kaliti', type: 'secret', value: '***', secret: true, editable: true },
];

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <GeneralSettingsPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('GeneralSettingsPage — service UP (read-only mode)', () => {
  it('renders grouped settings from the gateway registry, all locked', async () => {
    const reg = vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as never);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(reg).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    // every input is disabled and shows the lock hint
    expect(screen.getByDisplayValue('https://a.example.com')).toBeDisabled();
    expect(screen.getByDisplayValue('3')).toBeDisabled();
    expect(screen.getByDisplayValue('8090')).toBeDisabled();
    expect(screen.getAllByRole('img', { name: 'lock' }).length).toBe(3);
    // no save buttons anywhere — the UI must never offer a write path to the gateway
    expect(screen.queryByRole('button', { name: /Saqlash/ })).toBeNull();
  });

  it('shows the stop-to-edit banner with a link to the Ops page', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.stopToEdit'));
    expect(within(screen.getByRole('alert')).getByRole('button', { name: i18n.t('nav.ops') })).toBeInTheDocument();
  });

  it('does not render effect-class legend badges anymore', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.queryByText('Jonli')).toBeNull();
    expect(screen.queryByText('Restart kerak')).toBeNull();
    expect(screen.queryByText('Bloklangan')).toBeNull();
  });
});

describe('GeneralSettingsPage — service DOWN, ops session alive (edit mode)', () => {
  it('loads the table from the ops API instead of the gateway', async () => {
    const reg = vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as never);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(opsGet).toHaveBeenCalled();
    expect(reg).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('https://a.example.com')).toBeEnabled();
  });

  it('shows the applies-on-start note instead of effect-class badges', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.appliesOnStart'));
    expect(screen.queryByText('Jonli')).toBeNull();
  });

  it('shows exactly one global save button, disabled when nothing is edited', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    // no per-row save buttons anymore — only one global button
    const saveButtons = screen.getAllByRole('button', { name: /Saqlash/ });
    expect(saveButtons).toHaveLength(1);
    expect(saveButtons[0]).toBeDisabled();
  });

  it('typing enables the global save and PUTs ONLY the changed key(s)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /Saqlash/ })).toBeDisabled();
    const input = screen.getByDisplayValue('https://a.example.com');
    await userEvent.clear(input);
    await userEvent.type(input, 'https://b.example.com');
    // one dirty row → button enabled and shows the count
    const saveBtn = screen.getByRole('button', { name: /Saqlash \(1\)/ });
    expect(saveBtn).toBeEnabled();
    await userEvent.click(saveBtn);
    // PUT fires for the changed key only — not the untouched port, not the untouched masked secret
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.api.base-url', 'https://b.example.com'));
    expect(put).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(i18n.t('settings.savedCount', { count: 1 }))).toBeInTheDocument();
  });

  it('reports a failed key yet still attempts the remaining ones', async () => {
    const rows = [
      { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', value: 'https://a.example.com', secret: false, editable: true, stored: true },
      { key: 'gateway.retry.max-attempts', group: 'Retry/HTTP', label: 'Max attempts', type: 'number', value: '3', secret: false, editable: true, stored: true },
    ];
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(rows as never);
    const put = vi.spyOn(opsSettings, 'put')
      .mockRejectedValueOnce({ response: { data: { error: 'service-running' } } })
      .mockResolvedValueOnce(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    const urlInput = screen.getByDisplayValue('https://a.example.com');
    await userEvent.clear(urlInput);
    await userEvent.type(urlInput, 'https://b.example.com');
    const numInput = screen.getByDisplayValue('3');
    await userEvent.clear(numInput);
    await userEvent.type(numInput, '5');
    await userEvent.click(screen.getByRole('button', { name: /Saqlash \(2\)/ }));
    // both keys attempted even though the first rejected
    await waitFor(() => expect(put).toHaveBeenCalledTimes(2));
    expect(put).toHaveBeenCalledWith('gateway.api.base-url', 'https://b.example.com');
    expect(put).toHaveBeenCalledWith('gateway.retry.max-attempts', '5');
    // the failed key + its mapped service-running message surface in an error toast
    expect(await screen.findByText(/gateway\.api\.base-url/)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(i18n.t('settings.serviceRunning')))).toBeInTheDocument();
  });

  it('renders the env-default placeholder for a not-yet-stored registry key', async () => {
    const rows = [
      { key: 'gateway.source.base-url', group: 'Endpointlar', label: 'Source base-url', type: 'url', value: '', secret: false, editable: true, stored: false },
    ];
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(rows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('Source base-url')).toBeInTheDocument());
    expect(screen.getByPlaceholderText(i18n.t('settings.envDefault'))).toBeInTheDocument();
  });

  it('keeps non-editable rows locked with no save button', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('App port')).toBeInTheDocument());
    expect(screen.getByDisplayValue('8090')).toBeDisabled();
    const portRow = screen.getByText('App port').closest('div')!;
    expect(within(portRow as HTMLElement).getByRole('img', { name: 'lock' })).toBeInTheDocument();
    expect(within(portRow as HTMLElement).queryByRole('button')).toBeNull();
  });

  it('excludes an untouched masked secret from the dirty set, but sends a real edit', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('Shifrlash kaliti')).toBeInTheDocument());
    // masked + untouched → not dirty → global save disabled (never send the literal mask)
    expect(screen.getByRole('button', { name: /Saqlash/ })).toBeDisabled();
    // actually change the secret → it becomes dirty and PUTs the new value
    const secretInput = screen.getByDisplayValue('***');
    await userEvent.clear(secretInput);
    await userEvent.type(secretInput, 'new-key');
    await userEvent.click(screen.getByRole('button', { name: /Saqlash/ }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.settings.enc-key', 'new-key'));
    expect(put).toHaveBeenCalledTimes(1);
  });

  it('audit tab reads from the ops API when the gateway is down', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const gwAudit = vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([] as never);
    const opsAudit = vi.spyOn(opsSettings, 'getAudit').mockResolvedValue([
      { skey: 'gateway.api.base-url', old_value: 'a', new_value: 'b', changed_by: 'admin', changed_at: '2026-07-17 10:00' },
    ] as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('tab', { name: i18n.t('settings.audit') }));
    await waitFor(() => expect(opsAudit).toHaveBeenCalled());
    expect(gwAudit).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText('gateway.api.base-url')).toBeInTheDocument());
  });
});

describe('GeneralSettingsPage — typed-form / internal key dedup', () => {
  it('UP mode: hides typed-form keys from the registry but keeps generic keys', async () => {
    const reg = {
      settings: [
        { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', effectClass: 'LIVE', value: 'https://a.example.com', secret: false },
        { key: 'gateway.tenant.field-name', group: 'Tenant', label: 'Tenant field name', type: 'text', effectClass: 'LIVE', value: 'company_id', secret: false },
      ],
    };
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(reg as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.queryByText('Tenant field name')).toBeNull();
    expect(screen.queryByDisplayValue('company_id')).toBeNull();
  });

  it('DOWN mode: hides typed-form + internal keys from the ops list but keeps generic keys', async () => {
    const rows = [
      { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', value: 'https://a.example.com', secret: false, editable: true },
      { key: 'gateway.source.auth.type', group: 'Boshqa', label: 'gateway.source.auth.type', type: 'text', value: 'basic', secret: false, editable: true },
      { key: 'admin.reset-marker', group: 'Boshqa', label: 'admin.reset-marker', type: 'text', value: '1', secret: false, editable: true },
    ];
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(rows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.queryByText('gateway.source.auth.type')).toBeNull();
    expect(screen.queryByText('admin.reset-marker')).toBeNull();
  });
});

describe('GeneralSettingsPage — audit tab, service UP', () => {
  it('audit tab keeps reading from the gateway when it is up', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as never);
    const gwAudit = vi.spyOn(settingsApi, 'getAudit').mockResolvedValue([] as never);
    const opsAudit = vi.spyOn(opsSettings, 'getAudit').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('tab', { name: i18n.t('settings.audit') }));
    await waitFor(() => expect(gwAudit).toHaveBeenCalled());
    expect(opsAudit).not.toHaveBeenCalled();
  });
});
