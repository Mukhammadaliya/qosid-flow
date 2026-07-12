import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { TenantMappingPage } from './TenantMappingPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const rows = [
  { key: 'gateway.tenant.field-name', value: 'company_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.extra-field-name', value: 'filial_id', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.default-rest-tenant', value: '0', secret: false, updatedAt: '', updatedBy: '' },
  { key: 'gateway.tenant.trust-extra-tenant-header', value: 'false', secret: false, updatedAt: '', updatedBy: '' },
];

describe('TenantMappingPage', () => {
  it('loads the current tenant field-name value', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    render(<TenantMappingPage />);
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
  });

  it('shows the resolved tenantId from a sample after Test', async () => {
    vi.spyOn(settingsApi, 'getSettings').mockResolvedValue(rows as any);
    vi.spyOn(settingsApi, 'testTenantMapping').mockResolvedValue({ tenantId: 100, extraTenantId: 5, tenantField: 'company_id', extraField: 'filial_id' } as any);
    render(<TenantMappingPage />);
    await waitFor(() => expect(screen.getByDisplayValue('company_id')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Sinash' }));
    await waitFor(() => expect(screen.getByText('100')).toBeInTheDocument());
  });
});
