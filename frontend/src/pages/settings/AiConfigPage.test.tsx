import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { AiConfigPage } from './AiConfigPage';
import * as aiApi from '../../api/ai';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const opsRows = [
  { key: 'gateway.ai.base-url', value: 'https://llm/v1', secret: false, group: 'Boshqa', label: 'gateway.ai.base-url', type: 'text', editable: true },
  { key: 'gateway.ai.model', value: 'm1', secret: false, group: 'Boshqa', label: 'gateway.ai.model', type: 'text', editable: true },
  { key: 'gateway.ai.api-key', value: '***', secret: true, group: 'Boshqa', label: 'gateway.ai.api-key', type: 'text', editable: true },
];

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <AiConfigPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('AiConfigPage — service UP (read-only mode)', () => {
  it('loads via the gateway, disables inputs, and offers no save button', async () => {
    const get = vi.spyOn(aiApi, 'getAiConfig').mockResolvedValue({ baseUrl: 'https://llm/v1', apiKey: '***', model: 'm1' });
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('https://llm/v1')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Saqlash' })).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.stopToEdit'));
    expect(screen.getByRole('button', { name: 'Sinash' })).toBeEnabled();
  });
});

describe('AiConfigPage — service DOWN + ops session (edit mode)', () => {
  it('loads from the ops API and saves base-url/model without the untouched api key', async () => {
    const get = vi.spyOn(aiApi, 'getAiConfig').mockResolvedValue({ baseUrl: '', apiKey: '', model: '' });
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    expect(get).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('settings.appliesOnStart'));
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.ai.base-url', 'https://llm/v1'));
    expect(put).toHaveBeenCalledWith('gateway.ai.model', 'm1');
    expect(put).toHaveBeenCalledTimes(2); // api key left blank -> keep existing, no PUT
  });

  it('sends the api key too when a new one is typed', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText('API kalit'), 'sk-new');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.ai.api-key', 'sk-new'));
    expect(put).toHaveBeenCalledTimes(3);
  });

  it('disables the Test button (analyze is gateway-side)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Sinash' })).toBeDisabled();
  });
});
