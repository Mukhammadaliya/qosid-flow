import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { TenantMappingPage } from './TenantMappingPage';
import * as settingsApi from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const rows = [
  { key: 'gateway.tenant.field-name', value: 'company_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.extra-field-name', value: 'filial_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.default-rest-tenant', value: '0', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.trust-extra-tenant-header', value: 'false', secret: false, updatedAt: '', updatedBy: '' },
];

const opsRows = rows.map((r) => ({
  key: r.key, value: r.value, secret: false, group: 'Tenant', label: r.key, type: 'text', editable: true,
}));

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <TenantMappingPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('TenantMappingPage — service UP (read-only mode)', () => {
  it('loads via the gateway, disables inputs, and offers no save button', async () => {
    const get = vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('company_id')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Saqlash' })).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.stopToEdit'));
  });

  it('keeps the Test button working: shows the resolved tenantId from a sample', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'testTenantMapping').mockResolvedValue({ tenantId: 100, extraTenantId: 5, tenantField: 'company_id', extraField: 'filial_id' } as any);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Sinash' }));
    await waitFor(() => expect(screen.getByText('100')).toBeInTheDocument());
  });
});

describe('TenantMappingPage — service DOWN + ops session (edit mode)', () => {
  it('loads current values from the ops API instead of the gateway', async () => {
    const get = vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    expect(opsGet).toHaveBeenCalled();
    expect(get).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('company_id')).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.appliesOnStart'));
  });

  it('saves all four keys via PUT /ops/api/settings/*', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    const field = screen.getByDisplayValue('company_id');
    await userEvent.clear(field);
    await userEvent.type(field, 'org_id');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.tenant.field-name', 'org_id'));
    expect(put).toHaveBeenCalledWith('gateway.tenant.extra-field-name', 'filial_id');
    expect(put).toHaveBeenCalledWith('gateway.tenant.default-rest-tenant', '0');
    expect(put).toHaveBeenCalledWith('gateway.tenant.trust-extra-tenant-header', 'false');
    expect(put).toHaveBeenCalledTimes(4);
  });

  it('disables the Test button (gateway-side call needs a running service)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Sinash' })).toBeDisabled();
  });
});
